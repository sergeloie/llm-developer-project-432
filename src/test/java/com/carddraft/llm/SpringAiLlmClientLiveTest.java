package com.carddraft.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.carddraft.agents.CardDraft;
import com.carddraft.agents.CritiqueReport;
import com.carddraft.agents.SupplierFacts;
import com.carddraft.agents.Verdict;

/**
 * The named gap: no other test proves a real model returns something the parser can read.
 *
 * <p>Every other test substitutes this client, so the boundary between the stub and the provider
 * is covered only by hand. This suite closes it — one real extraction and one real review against
 * the local server.
 *
 * <p>Excluded from the default build with the {@code live-model} tag, because it needs a running
 * model server, takes seconds per call, and costs nothing when the server is absent.
 * Run it with {@code -DincludeTags=live-model}.
 */
@Tag("live-model")
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {
        "spring.ai.openai.base-url=${CARD_LLM_BASE_URL:http://127.0.0.1:1234}",
        "spring.ai.openai.api-key=${CARD_LLM_API_KEY:lm-studio}"
})
class SpringAiLlmClientLiveTest {

    @Container
    static final PostgreSQLContainer DATABASE = new PostgreSQLContainer("pgvector/pgvector:pg17")
            .withDatabaseName("card")
            .withUsername("card")
            .withPassword("card");

    @org.springframework.test.context.DynamicPropertySource
    static void datasource(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("card.db.url", DATABASE::getJdbcUrl);
        registry.add("card.db.username", DATABASE::getUsername);
        registry.add("card.db.password", DATABASE::getPassword);
    }

    @Autowired
    LlmClient llmClient;

    private static final String SUPPLIER_TEXT =
            "Блендер погружной МиксерПро 800. Мощность 800 Вт, питание 220 В. "
            + "6 скоростей плюс турбо. Металлическая ножка. Гарантия 24 месяца.";

    @Test
    void extractsStructuredFactsFromRealSupplierText() {
        SupplierFacts facts = llmClient.extractFacts(SUPPLIER_TEXT);

        assertThat(facts.productName()).isNotBlank();
        assertThat(facts.characteristics()).isNotEmpty();
        assertThat(facts.characteristics().values())
                .as("the real model must return the values, not echo the schema")
                .anySatisfy(value -> assertThat(value).contains("800"));
    }

    @Test
    void reviewsARealDraftAgainstRealFacts() {
        SupplierFacts facts = new SupplierFacts("Блендер МиксерПро 800",
                java.util.Map.of("Мощность", "800 Вт"), List.of("Цвет"));
        CardDraft draft = new CardDraft(
                "Блендер погружной МиксерПро 800 с мощностью 800 Вт и чашей из нержавеющей стали",
                "Погружной блендер для ежедневного приготовления.",
                java.util.Map.of("Мощность", "800 Вт"),
                List.of("Шесть скоростей", "Турбо-режим"));

        CritiqueReport report = llmClient.reviewDraft(facts, draft);

        assertThat(report.verdict()).isIn(Verdict.APPROVE, Verdict.REGENERATE);
        if (report.verdict() == Verdict.REGENERATE) {
            assertThat(report.issues()).as("a rejection must say why").isNotEmpty();
        }
    }
}
