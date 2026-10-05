package com.carddraft.metrics;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.ObjectMapper;

/**
 * The reference set, as data the harness can measure against.
 *
 * <p>Read from {@code data/golden_cards.json} rather than restated in code. The set is the
 * assignment's, and a copy of it in Java would be a second version that drifts: the day a
 * characteristic is corrected in the file, a harness reading its own copy would keep measuring
 * against the old value and report a miss for a card that is right.
 *
 * @param defaults    the subset a default run covers, declared by the set itself rather than by the
 *                    harness — a local model is slow enough that measuring everything is a measure
 *                    nobody performs
 */
public record ReferenceSet(Map<String, Map<String, String>> characteristics,
                           Map<String, Map<String, Object>> probes,
                           List<String> defaults) {

    public static ReferenceSet load(Path golden) throws IOException {
        Map<String, Object> root = new ObjectMapper()
                .readValue(Files.readString(golden), Map.class);

        Map<String, Map<String, String>> characteristics = new LinkedHashMap<>();
        Map<String, Map<String, Object>> probes = new LinkedHashMap<>();

        Map<String, Map<String, Object>> documents = castDocuments(root.get("documents"));
        documents.forEach((document, body) -> {
            if (body != null) {
                characteristics.put(document, castStrings(body.get("characteristics")));
                if (body.get("source_probes") instanceof Map<?, ?> probeMap) {
                    probes.put(document, castNested(probeMap));
                }
            }
        });

        List<String> defaults = root.get("eval_defaults") instanceof List<?> list
                ? list.stream().map(String::valueOf).toList()
                : List.copyOf(characteristics.keySet());

        return new ReferenceSet(characteristics, probes, defaults);
    }

    /** Every document the set describes, in file order so two runs measure the same things. */
    public List<String> documents() {
        return List.copyOf(characteristics.keySet());
    }

    public Map<String, String> characteristicsOf(String document) {
        return characteristics.getOrDefault(document, Map.of());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> castDocuments(Object value) {
        Map<String, Object> raw = (Map<String, Object>) value;
        Map<String, Map<String, Object>> documents = new LinkedHashMap<>();
        raw.forEach((document, body) -> documents.put(document, (Map<String, Object>) body));
        return documents;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> castStrings(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, String> strings = new LinkedHashMap<>();
        map.forEach((key, entry) -> strings.put(String.valueOf(key), entry == null ? "" : String.valueOf(entry)));
        return strings;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castNested(Map<?, ?> map) {
        Map<String, Object> nested = new LinkedHashMap<>();
        map.forEach((key, value) -> nested.put(String.valueOf(key), value));
        return nested;
    }
}