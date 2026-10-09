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
import org.springframework.http.MediaType;
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
import com.carddraft.repositories.JobsRepository;

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

    @Autowired
    JobsRepository jobs;

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
    void aLowConfidenceCardIsFlaggedAsWaitingForAPersonRatherThanDone() {
        given(llmClient.extractFacts(anyString()))
                .willReturn(new SupplierFacts("Blender", Map.of("Power", "800 W"), List.of()));
        given(llmClient.draftCard(any(), any()))
                .willReturn(new ProductCard("Blender", "A blender.",
                Map.of("Power", "800 W"), List.of("Quiet"), List.of(), 0.4, Map.of()));
        given(llmClient.reviewDraft(any(), any()))
                .willReturn(new CritiqueReport(Verdict.APPROVE, List.of()));

        ResponseEntity<Map> submitted = rest.postForEntity("/jobs",
                Map.of("supplierText", "Blender. Power 800 W."), Map.class);
        String jobId = (String) submitted.getBody().get("id");

        // The card is only handed back once the person has decided: awaiting_human carries the
        // wait, the finished job carries the card — so the flag is asserted where the card is.
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(rest.getForObject("/jobs/" + jobId, Map.class))
                        .containsEntry("status", "awaiting_human"));

        rest.postForEntity("/jobs/" + jobId + "/decision", Map.of("decision", "approve"), Map.class);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            Map<String, Object> finished = rest.getForObject("/jobs/" + jobId, Map.class);
            assertThat(finished).containsEntry("status", "approved");
            assertThat(finished)
                    .as("below the configured threshold the card waited for a person rather "
                            + "than reading as done, and the finished job says so")
                    .containsEntry("awaitingHuman", true);
            assertThat(((Number) finished.get("confidence")).doubleValue()).isEqualTo(0.4);
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

    @Test
    void aDecisionFinishesTheJobAndTheCardBecomesReadable() {
        String jobId = submitAndAwaitAHuman("decision-approves");

        rest.postForEntity("/jobs/" + jobId + "/decision", Map.of("decision", "approve"), Map.class);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            Map<String, Object> finished = rest.getForObject("/jobs/" + jobId, Map.class);
            assertThat(finished).containsEntry("status", "approved");
            assertThat(String.valueOf(finished.get("result")))
                    .as("an approved job has to be able to hand back the card, or deciding bought "
                            + "nothing a caller can read")
                    .contains("Blender MixerPro 800");
        });
    }

    @Test
    void aRejectionIsAnOutcomeWithItsOwnStateRatherThanAFailure() {
        String jobId = submitAndAwaitAHuman("decision-rejects");

        rest.postForEntity("/jobs/" + jobId + "/decision", Map.of("decision", "reject"), Map.class);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(rest.getForObject("/jobs/" + jobId, Map.class))
                        .containsEntry("status", "rejected"));
    }

    @Test
    void aDecisionThatIsNeitherApproveNorRejectIsRefused() {
        String jobId = submitAndAwaitAHuman("decision-nonsense");

        ResponseEntity<Map> response = rest.postForEntity("/jobs/" + jobId + "/decision",
                Map.of("decision", "maybe"), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("detail").toString())
                .as("the caller has to be told what it may say, not merely that it was wrong")
                .contains("approve")
                .contains("reject");
    }

    @Test
    void aDecisionBodyWithoutAValueIsRefusedWithTheFieldNamed() {
        String jobId = submitAndAwaitAHuman("decision-missing");

        ResponseEntity<Map> response = rest.postForEntity("/jobs/" + jobId + "/decision",
                Map.of(), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getHeaders().getContentType())
                .as("RFC-7807: a body that fails validation is a ProblemDetail")
                .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody().get("detail").toString())
                .as("the offending field is named rather than the body being declared invalid")
                .contains("decision");
        assertThat(rest.getForObject("/jobs/" + jobId, Map.class))
                .as("a refused decision must not have signalled anything")
                .containsEntry("status", "awaiting_human");
    }

    @Test
    void aDecisionIsAcceptedRegardlessOfCaseAndSurroundingSpace() {
        String jobId = submitAndAwaitAHuman("decision-shouting");

        ResponseEntity<Map> response = rest.postForEntity("/jobs/" + jobId + "/decision",
                Map.of("decision", "  APPROVE  "), Map.class);

        assertThat(response.getStatusCode())
                .as("a person's answer, not a machine's enum: case and space are ignored")
                .isEqualTo(HttpStatus.ACCEPTED);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(rest.getForObject("/jobs/" + jobId, Map.class))
                        .containsEntry("status", "approved"));
    }

    @Test
    void aDecisionForAnUnknownJobIsNotFound() {
        ResponseEntity<Map> response = rest.postForEntity("/jobs/no-such-job/decision",
                Map.of("decision", "approve"), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void anEmptySubmitIsRefusedAtTheDoorWithAProblemDetailBody() {
        ResponseEntity<Map> response = rest.postForEntity("/jobs",
                Map.of("supplierText", "   "), Map.class);

        assertThat(response.getStatusCode())
                .as("a job with neither text nor documents to work from is refused, "
                        + "not accepted and failed three minutes later")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getHeaders().getContentType())
                .as("RFC-7807: every refusal is a ProblemDetail, not a hand-rolled map")
                .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody()).containsEntry("status", 400);
        assertThat(response.getBody().get("detail").toString()).contains("supplierText");
    }

    @Test
    void aDecisionForAJobWhoseProcessIsGoneIsA409Conflict() {
        String jobId = "process-gone-" + System.nanoTime();
        jobs.createWithId(jobId, "awaiting_human", "{}");
        jobs.complete(jobId, "awaiting_human", "{\"title\":\"a draft\"}");

        ResponseEntity<Map> response = rest.postForEntity("/jobs/" + jobId + "/decision",
                Map.of("decision", "approve"), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getHeaders().getContentType())
                .as("RFC-7807: the conflict is a ProblemDetail")
                .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody()).containsEntry("status", 409);
    }

    @Test
    void aDecisionAgainstAMetricsStyleJobStatusIsA4xxRatherThanA500() {
        // The metrics harness stores its runs in the same jobs table with statuses the
        // card-job enum does not know, so a decision must not blow up the enum lookup.
        String jobId = "metrics-" + System.nanoTime();
        jobs.createWithId(jobId, "metrics_complete", "{}");
        jobs.complete(jobId, "metrics_complete", "{\"title\":\"Kettle\"}");

        ResponseEntity<Map> response = rest.postForEntity("/jobs/" + jobId + "/decision",
                Map.of("decision", "approve"), Map.class);

        assertThat(response.getStatusCode().value())
                .as("an unknown job status must never surface as a 500: "
                        + "there is no process waiting on a decision for a metrics job")
                .isBetween(400, 499);
        assertThat(response.getHeaders().getContentType())
                .as("RFC-7807: the refusal is a ProblemDetail")
                .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @Test
    void approvingAJobWithNoDraftIsRefusedWithoutSignallingTheWorkflow() {
        // The row a job escalated before generation leaves behind: waiting on a person,
        // with no generation attempt and no draft recorded.
        String jobId = "escalated-" + System.nanoTime();
        jobs.createWithId(jobId, "awaiting_human", "{}");

        ResponseEntity<Map> response = rest.postForEntity("/jobs/" + jobId + "/decision",
                Map.of("decision", "approve"), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getHeaders().getContentType())
                .as("RFC-7807: the refusal is a ProblemDetail")
                .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(rest.getForObject("/jobs/" + jobId, Map.class))
                .as("the refusal must not have signalled anything: the job still waits")
                .containsEntry("status", "awaiting_human");
    }

    @Test
    void approvingAJobWhoseRecordedDraftIsAnEmptyObjectIsRefused() {
        String jobId = "empty-draft-" + System.nanoTime();
        jobs.createWithId(jobId, "awaiting_human", "{}");
        jobs.complete(jobId, "awaiting_human", "{}");

        ResponseEntity<Map> response = rest.postForEntity("/jobs/" + jobId + "/decision",
                Map.of("decision", "approve"), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getHeaders().getContentType())
                .as("RFC-7807: the refusal is a ProblemDetail")
                .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(rest.getForObject("/jobs/" + jobId, Map.class))
                .containsEntry("status", "awaiting_human");
    }

    @Test
    void aFailedCardJobReportsItsFailureReasonThroughTheStatusEndpoint() {
        given(llmClient.extractFacts(anyString()))
                .willThrow(new IllegalStateException("provider unreachable"));

        ResponseEntity<Map> submitted = rest.postForEntity("/jobs",
                Map.of("supplierText", "Blender MixerPro 800. Power 800 W."), Map.class);
        String jobId = (String) submitted.getBody().get("id");

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            Map<String, Object> status = rest.getForObject("/jobs/" + jobId, Map.class);
            assertThat(status)
                    .as("a card job that fails asynchronously reports failed like a document job")
                    .containsEntry("status", "failed");
            assertThat(status.get("error").toString())
                    .as("and carries the reason, so metrics and alerting can read it")
                    .contains("provider unreachable");
        });
    }

    private String submitAndAwaitAHuman(String idempotencyKey) {
        givenAnApprovingReviewer();
        HttpHeaders headers = new HttpHeaders();
        headers.set("Idempotency-Key", idempotencyKey + "-" + System.nanoTime());

        ResponseEntity<Map> submitted = rest.exchange("/jobs", HttpMethod.POST,
                new HttpEntity<>(Map.of("supplierText", "Blender MixerPro 800. Power 800 W."), headers),
                Map.class);
        String jobId = (String) submitted.getBody().get("id");

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(rest.getForObject("/jobs/" + jobId, Map.class))
                        .containsEntry("status", "awaiting_human"));
        return jobId;
    }
}
