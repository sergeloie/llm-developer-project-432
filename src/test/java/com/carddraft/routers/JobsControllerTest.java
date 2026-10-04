package com.carddraft.routers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import com.carddraft.temporal.CardActivities;
import com.carddraft.temporal.CardWorkflowImpl;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.carddraft.agents.ProductCard;
import com.carddraft.agents.CritiqueReport;
import com.carddraft.agents.SupplierFacts;
import com.carddraft.agents.Verdict;
import com.carddraft.llm.LlmClient;

/**
 * The asynchronous path, end to end: HTTP, the database, and a real process engine.
 *
 * <p>The model client is substituted, so this proves the wiring — 202 returned immediately, state
 * reaching the database where a client can read it, a repeated request not buying a second job —
 * without paying for a generation. The engine runs in-process, so no Docker is involved beyond
 * the database.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {
        // The application does not start a worker here. This test supplies its own in-memory
        // engine, which is per-class and therefore cannot be shut down by another test's context
        // closing - which is what made an embedded server fail intermittently with no error
        // anywhere, since a worker polling a dead server simply never hears about a workflow.
        "card.temporal.worker.enabled=false"
})
class JobsControllerTest {

    /** One isolated engine for this class, started once and stopped with it. */
    static final TestWorkflowEnvironment ENGINE = TestWorkflowEnvironment.newInstance();

    @Container
    static final PostgreSQLContainer DATABASE = new PostgreSQLContainer("pgvector/pgvector:pg17")
            .withDatabaseName("card")
            .withUsername("card")
            .withPassword("card");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("card.db.url", DATABASE::getJdbcUrl);
        registry.add("card.db.username", DATABASE::getUsername);
        registry.add("card.db.password", DATABASE::getPassword);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class EngineConfiguration {

        @Bean
        WorkflowServiceStubs workflowServiceStubs() {
            return ENGINE.getWorkflowServiceStubs();
        }

        @Bean
        WorkflowClient workflowClient(WorkflowServiceStubs stubs) {
            return WorkflowClient.newInstance(stubs);
        }

        @Bean
        WorkerFactory workerFactory(WorkflowClient client, CardActivities activities) {
            WorkerFactory factory = WorkerFactory.newInstance(client);
            Worker worker = factory.newWorker("card-drafting");
            worker.registerWorkflowImplementationTypes(CardWorkflowImpl.class);
            worker.registerActivitiesImplementations(activities);
            factory.start();
            return factory;
        }
    }

    @BeforeAll
    static void startEngine() {
        ENGINE.start();
    }

    @AfterAll
    static void stopEngine() {
        ENGINE.shutdown();
    }


    @MockitoBean
    LlmClient llmClient;

    @Autowired
    TestRestTemplate rest;

    private void givenAnApprovingReviewer() {
        given(llmClient.extractFacts(anyString()))
                .willReturn(new SupplierFacts("Blender MixerPro 800", Map.of("Power", "800 W"), List.of()));
        given(llmClient.draftCard(any(), any()))
                .willReturn(new ProductCard("Blender MixerPro 800", "A blender.",
                Map.of("Power", "800 W"), List.of("Quiet"), List.of(), 0.9, Map.of()));
        given(llmClient.reviewDraft(any(), any()))
                .willReturn(new CritiqueReport(Verdict.APPROVE, List.of()));
    }

    @Test
    void answersImmediatelyWithAJobIdentifierAndThenReachesAwaitingAHuman() {
        givenAnApprovingReviewer();

        ResponseEntity<Map> submitted = rest.postForEntity("/jobs",
                Map.of("supplierText", "Blender MixerPro 800. Power 800 W."), Map.class);

        assertThat(submitted.getStatusCode())
                .as("202: accepted for processing, because the resource does not exist yet")
                .isEqualTo(HttpStatus.ACCEPTED);
        String jobId = (String) submitted.getBody().get("id");
        assertThat(jobId).isNotBlank();

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            Map<String, Object> status = rest.getForObject("/jobs/" + jobId, Map.class);
            assertThat(status).containsEntry("status", "awaiting_human");
        });
    }

    @Test
    void theSameIdempotencyKeyReturnsTheSameJobRatherThanPayingTwice() {
        givenAnApprovingReviewer();
        HttpHeaders headers = new HttpHeaders();
        headers.set("Idempotency-Key", "double-click-" + System.nanoTime());

        ResponseEntity<Map> first = rest.exchange("/jobs", HttpMethod.POST,
                new HttpEntity<>(Map.of("supplierText", "a blender"), headers), Map.class);
        ResponseEntity<Map> second = rest.exchange("/jobs", HttpMethod.POST,
                new HttpEntity<>(Map.of("supplierText", "a blender"), headers), Map.class);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(second.getBody().get("id")).isEqualTo(first.getBody().get("id"));
    }

    @Test
    void anUnknownJobIsNotFound() {
        ResponseEntity<Map> response = rest.getForEntity("/jobs/no-such-job", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
