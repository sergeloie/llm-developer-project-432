package com.carddraft.agents;

import java.util.List;
import java.util.Map;

/**
 * The draft card: the service's deliverable, and explicitly not a publish-ready card.
 *
 * <p>A human verifies this in a minute because every value can be traced, and what is absent is
 * listed rather than filled in. Sources and confidence arrive with the result contract.
 */
public record CardDraft(
        String title,
        String description,
        Map<String, String> characteristics,
        List<String> benefits) {

    public CardDraft {
        characteristics = characteristics == null ? Map.of() : Map.copyOf(characteristics);
        benefits = benefits == null ? List.of() : List.copyOf(benefits);
    }
}
