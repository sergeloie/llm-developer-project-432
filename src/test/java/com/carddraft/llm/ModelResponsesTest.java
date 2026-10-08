package com.carddraft.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The four shapes a model response arrives in, and what happens when none of them fits.
 *
 * <p>All four are real: local models wrap JSON in markdown fences despite being told not to, put a
 * sentence in front of it and a note after it, and occasionally produce nothing usable. The first
 * three must be recovered silently — the card is right and only the packaging is wrong. The
 * fourth must fail loudly, because a formatting problem reported as an empty result sends the
 * reader looking in the wrong place.
 */
class ModelResponsesTest {

    @Test
    void readsCleanJson() {
        assertThat(ModelResponses.jsonObject("{\"a\":1}", "op")).isEqualTo("{\"a\":1}");
    }

    @Test
    void readsJsonWrappedInMarkdownFences() {
        String response = """
                Here is the card:
                ```json
                {"title":"Blender"}
                ```
                """;

        assertThat(ModelResponses.jsonObject(response, "op")).isEqualTo("{\"title\":\"Blender\"}");
    }

    @Test
    void readsJsonSurroundedByProse() {
        String response = """
                Sure! Based on the document I found the following.
                {"title":"Blender","confidence":0.9}
                Let me know if you need anything else.
                """;

        assertThat(ModelResponses.jsonObject(response, "op"))
                .isEqualTo("{\"title\":\"Blender\",\"confidence\":0.9}");
    }

    @Test
    void readsJsonFromInsideAnUnlabelledFence() {
        assertThat(ModelResponses.jsonObject("```\n{\"title\":\"Blender\"}\n```", "op"))
                .isEqualTo("{\"title\":\"Blender\"}");
    }

    @Test
    void trailingProseWithBracesIsNotSwallowedIntoTheObject() {
        String response = "{\"title\":\"Blender\",\"details\":{\"power\":\"800 W\"}} "
                + "hope this helps {not json";

        assertThat(ModelResponses.jsonObject(response, "op"))
                .isEqualTo("{\"title\":\"Blender\",\"details\":{\"power\":\"800 W\"}}");
    }

    @Test
    void bracesInsideStringValuesDoNotEndTheObject() {
        String response = "Result: {\"title\":\"Blender (800 {W})\",\"confidence\":0.9} done.";

        assertThat(ModelResponses.jsonObject(response, "op"))
                .isEqualTo("{\"title\":\"Blender (800 {W})\",\"confidence\":0.9}");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "I could not find any product information in the text.",
            "The document does not contain a product."
    })
    void refusesProseWithNoJsonAndNamesTheOperation(String response) {
        ModelResponseFormatException thrown =
                org.junit.jupiter.api.Assertions.assertThrows(ModelResponseFormatException.class,
                        () -> ModelResponses.jsonObject(response, "draftCard"));

        assertThat(thrown)
                .hasMessageContaining("draftCard")
                .hasMessageContaining("no JSON object");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void anEmptyResponseIsReportedAsEmptyRatherThanAsUnparseable(String response) {
        ModelResponseFormatException thrown =
                org.junit.jupiter.api.Assertions.assertThrows(ModelResponseFormatException.class,
                        () -> ModelResponses.jsonObject(response, "extractFacts"));

        assertThat(thrown.getMessage())
                .contains("extractFacts")
                .contains("empty");
    }

    @Test
    void quotesOnlyTheStartOfALongUnusableResponse() {
        String noise = "x".repeat(5000);

        ModelResponseFormatException thrown =
                org.junit.jupiter.api.Assertions.assertThrows(ModelResponseFormatException.class,
                        () -> ModelResponses.jsonObject(noise, "reviewDraft"));

        assertThat(thrown.getMessage())
                .contains("reviewDraft")
                .doesNotContain("x".repeat(300));
    }

    @Test
    void theSchemaIsDerivedFromTheRecordSoItCannotDrift() {
        String schema = ResultContract.schemaFor(com.carddraft.agents.ProductCard.class);

        assertThat(schema)
                .contains("\"title\"")
                .contains("\"characteristics\"")
                .contains("\"missingFields\"")
                .contains("\"confidence\"")
                .contains("\"sources\"")
                .as("every component is required, so the model cannot omit one and be half-right")
                .contains("\"required\"");
        for (var component : com.carddraft.agents.ProductCard.class.getRecordComponents()) {
            assertThat(schema).contains(component.getName());
        }
    }

    @Test
    void nestedRecordsBecomeNestedObjectsInTheSchema() {
        String schema = ResultContract.schemaFor(com.carddraft.agents.CritiqueReport.class);

        assertThat(schema).contains("\"verdict\"").contains("\"issues\"");
    }

    @Test
    void theContractAcceptsAWellFormedCard() {
        var card = new com.carddraft.agents.ProductCard(
                "Blender MixerPro 800",
                "A submerged blender for everyday cooking.",
                Map.of("Power", "800 W"),
                List.of("Six speeds"),
                List.of("Colour"),
                0.85,
                Map.of("Power", "C1"));

        assertThat(ResultContract.problemsWith(card)).isEmpty();
    }

    @Test
    void aSourceThatIsNotAReferenceIsSentBack() {
        var card = new com.carddraft.agents.ProductCard(
                "Blender", "A blender.",
                Map.of("Power", "800 W"),
                List.of("Fast"), List.of(), 0.9,
                Map.of("Power", "This blender has a power of 800 W"));

        assertThat(ResultContract.sourcesProblems(card))
                .anyMatch(p -> p.contains("'Power'") && p.contains("not a fragment reference"));
        assertThat(ResultContract.problemsWith(card)).isNotEmpty();
    }

    @Test
    void theContractNamesEveryWayACardCanBeWrong() {
        var card = new com.carddraft.agents.ProductCard(
                "B".repeat(80),
                " ",
                Map.of("Power", "  "),
                List.of(),
                List.of("Power"),
                1.4,
                Map.of("Colour", "C9"));

        assertThat(ResultContract.problemsWith(card))
                .anyMatch(p -> p.contains("at most 60"))
                .anyMatch(p -> p.contains("description is empty"))
                .anyMatch(p -> p.contains("'Power' has an empty value"))
                .anyMatch(p -> p.contains("both characteristics and missingFields"))
                .anyMatch(p -> p.contains("between 0 and 1"))
                .anyMatch(p -> p.contains("'Colour'"))
                .anyMatch(p -> p.contains("no benefits"));
    }

    @Test
    void aCitationLabelAsACharacteristicValueIsSentBack() {
        var card = new com.carddraft.agents.ProductCard(
                "Blender", "A blender.",
                Map.of("Power", "C1", "Weight", "[C2]"),
                List.of("Fast"), List.of(), 0.9, Map.of("Power", "C1", "Weight", "C2"));

        assertThat(ResultContract.problemsWith(card))
                .anyMatch(p -> p.contains("'Power'") && p.contains("as its value"))
                .anyMatch(p -> p.contains("'Weight'") && p.contains("as its value"));
    }

    @Test
    void aCardBelowTheThresholdWaitsForAPerson() {        var weak = new com.carddraft.agents.ProductCard("T", "d", Map.of(), List.of("b"), List.of(), 0.4, Map.of());
        var strong = new com.carddraft.agents.ProductCard("T", "d", Map.of(), List.of("b"), List.of(), 0.9, Map.of());

        assertThat(weak.awaitsHuman(0.7)).as("low confidence must not read as done").isTrue();
        assertThat(strong.awaitsHuman(0.7)).isFalse();
    }
}
