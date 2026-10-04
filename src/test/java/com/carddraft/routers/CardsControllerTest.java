package com.carddraft.routers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.carddraft.agents.CardDraft;
import com.carddraft.agents.CritiqueReport;
import com.carddraft.agents.SupplierFacts;
import com.carddraft.agents.Verdict;
import com.carddraft.llm.LlmClient;

/**
 * The first slice that crosses every layer: HTTP to model boundary to pipeline to repository.
 *
 * <p>The model client is substituted, so this test proves the wiring and the contract without a
 * model and without spending anything. The pipeline module's own tests cover its behaviour in
 * detail; this one exists to prove the layers connect.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers(disabledWithoutDocker = true)

@TestPropertySource(properties = "test.context-id=cards")
class CardsControllerTest {

    @Container
    static final PostgreSQLContainer DATABASE = new PostgreSQLContainer("pgvector/pgvector:pg17")
            .withDatabaseName("card")
            .withUsername("card")
            .withPassword("card");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("card.db.url", DATABASE::getJdbcUrl);
        registry.add("card.db.username", DATABASE::getUsername);
        registry.add("card.db.password", DATABASE::getPassword);
    }

    @MockitoBean
    LlmClient llmClient;

    @Autowired
    TestRestTemplate rest;

    @Test
    void returnsADraftCardForSupplierText() {
        given(llmClient.extractFacts(anyString()))
                .willReturn(new SupplierFacts("Blender MixerPro 800",
                        Map.of("Power", "800 W"), List.of()));
        given(llmClient.draftCard(any(), any()))
                .willReturn(new CardDraft("Blender MixerPro 800",
                        "A submerged blender for everyday cooking.",
                        Map.of("Power", "800 W"),
                        List.of("Six speeds plus turbo")));
        given(llmClient.reviewDraft(any(), any()))
                .willReturn(new CritiqueReport(Verdict.APPROVE, List.of()));

        ResponseEntity<CardDraft> response = rest.postForEntity(
                "/cards", Map.of("supplierText", "Blender MixerPro 800. Power 800 W."), CardDraft.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().title()).isEqualTo("Blender MixerPro 800");
        assertThat(response.getBody().characteristics()).containsEntry("Power", "800 W");
    }
}
