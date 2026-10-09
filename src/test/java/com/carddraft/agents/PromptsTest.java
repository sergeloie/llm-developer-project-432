package com.carddraft.agents;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

/**
 * The prompts, as the model will read them.
 *
 * <p>The repair prompt's whole promise is that every field except the one being repaired must come
 * back "exactly as it appears below" — so what appears below must be the same card, parseable back
 * into the identical record. Lists, escapes and line breaks have to survive the round trip.
 */
class PromptsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void theCardEmbeddedInARepairPromptIsRealJson() throws Exception {
        ProductCard card = new ProductCard(
                "Blender MixerPro 800",
                "Line one of the description.\nLine two with \"quotes\" and a brace: }",
                Map.of("Power", "800 W", "Notes", "multi\nline value"),
                List.of("Quiet", "Compact"),
                List.of("Colour"),
                0.42,
                Map.of("Power", "C7"));

        String prompt = Prompts.repairField(mapper.writeValueAsString(card), "title",
                "the title is too long");
        String block = prompt.substring(
                prompt.indexOf("CURRENT CARD:") + "CURRENT CARD:".length()).strip();

        ProductCard reread = mapper.readValue(block, ProductCard.class);

        assertThat(reread)
                .as("the model is told to return every other field exactly as it appears below; "
                        + "below must be a card that parses back to the same values")
                .isEqualTo(card);
    }

    @Test
    void theThreeRolePromptsNameTheSameTitleLimit() {
        String limit = String.valueOf(ProductCard.MAX_TITLE_LENGTH);

        assertThat(Prompts.generator("{}", List.of())).contains("at most " + limit + " characters");
        assertThat(Prompts.critic("{}", "{}")).contains("at most " + limit + " characters");
        assertThat(Prompts.generatorFromContext("[C1] nothing", List.of()))
                .contains("at most " + limit + " characters");
    }
}