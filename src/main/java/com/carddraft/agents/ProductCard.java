package com.carddraft.agents;

import java.util.List;
import java.util.Map;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * The draft card as a contract the application relies on the way it relies on its own database.
 *
 * <p>Every constraint here is a decision the service will not make on the model's behalf. A title
 * longer than the limit is not shortened silently; a field with no value is not dropped silently;
 * a field that is both a characteristic and missing is a contradiction, not a nuance. Each of
 * those becomes a message sent back to the model rather than a value invented here.
 *
 * <p>{@code confidence} is the field that makes refusal possible. A card whose confidence is below
 * the configured threshold is not approved — it waits for a person, which is the only honest
 * response to a weak result.
 *
 * <p>The cross-field rules — no blank characteristic values, no field in both lists, every source
 * naming a characteristic that exists — are not expressible as annotations and live in
 * {@code ResultContract}, so that the model receives the same wording we check.
 */
public record ProductCard(

        @NotBlank @Size(max = MAX_TITLE_LENGTH,
                message = "the title must be at most " + MAX_TITLE_LENGTH + " characters")
        String title,

        @NotBlank
        String description,

        @NotNull
        Map<String, String> characteristics,

        @NotNull
        List<String> benefits,

        @NotNull
        List<String> missingFields,

        @NotNull @DecimalMin("0.0") @DecimalMax("1.0")
        Double confidence,

        @NotNull
        Map<String, String> sources) {

    /** The longest title a card may carry, shared by the annotation, the contract and the prompts. */
    public static final int MAX_TITLE_LENGTH = 60;

    public ProductCard {
        characteristics = characteristics == null ? Map.of() : Map.copyOf(characteristics);
        benefits = benefits == null ? List.of() : List.copyOf(benefits);
        missingFields = missingFields == null ? List.of() : List.copyOf(missingFields);
        sources = sources == null ? Map.of() : Map.copyOf(sources);
    }

    public boolean awaitsHuman(double threshold) {
        return confidence == null || confidence < threshold;
    }

    /**
     * Whether a serialised card is a real draft rather than the empty object a job starts from.
     *
     * <p>One rule, because two callers need it and disagreeing copies of it would mean an approval
     * one path refuses and another records as a success with nothing to show. An empty object is
     * not a weak card, it is no card: it must not be approved by a person's signal or by the
     * workflow that receives it.
     */
    public static boolean isDraftJson(String json) {
        return json != null && !json.isBlank() && !"{}".equals(json.strip());
    }
}
