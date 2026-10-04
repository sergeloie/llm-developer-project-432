package com.carddraft.agents;

import java.util.List;
import java.util.Map;

/**
 * What the extraction role could establish from the source text, including what it could not.
 *
 * <p>Absent data is named rather than omitted or guessed. That list is the single most valuable
 * output of the whole service: it is what makes a draft honest.
 *
 * <p>Deliberately unvalidated for now. Constraints on title length, confidence range and the
 * overlap between characteristics and missing fields arrive with the result contract, not here.
 */
public record SupplierFacts(
        String productName,
        Map<String, String> characteristics,
        List<String> missingFields) {

    public SupplierFacts {
        characteristics = characteristics == null ? Map.of() : Map.copyOf(characteristics);
        missingFields = missingFields == null ? List.of() : List.copyOf(missingFields);
    }
}
