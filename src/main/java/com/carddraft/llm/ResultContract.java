package com.carddraft.llm;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

import com.carddraft.agents.ProductCard;

/**
 * Checks a result against the contract and says what is wrong in words the model can act on.
 *
 * <p>One place knows the rules, and it is the place that phrases them. A validator that returns
 * "constraint violated" forces a second translation step before the message can be sent back, and
 * the two drift. Here the same sentence is both the check and the instruction.
 */
public final class ResultContract {

    private ResultContract() {
    }

    /**
     * @return the problems, empty when the result satisfies the contract. An empty list means
     *         usable; a non-empty one means send it back with these sentences attached.
     */
    public static List<String> problemsWith(ProductCard card) {
        List<String> problems = new java.util.ArrayList<>();

        if (card.title() == null || card.title().isBlank()) {
            problems.add("the title is empty");
        } else if (card.title().length() > 60) {
            problems.add("the title is " + card.title().length()
                    + " characters long; it must be at most 60. Shorten it.");
        }

        if (card.description() == null || card.description().isBlank()) {
            problems.add("the description is empty");
        }

        card.characteristics().forEach((name, value) -> {
            if (value == null || value.isBlank()) {
                problems.add("the characteristic '" + name + "' has an empty value; "
                        + "either give it a value or move it to missingFields");
            }
        });

        for (String missing : card.missingFields()) {
            if (card.characteristics().containsKey(missing)) {
                problems.add("'" + missing + "' appears in both characteristics and missingFields; "
                        + "a field cannot be present and absent at once");
            }
        }

        card.sources().forEach((name, chunkId) -> {
            if (!card.characteristics().containsKey(name)) {
                problems.add("sources names '" + name + "' but there is no such characteristic");
            }
            if (chunkId == null || chunkId.isBlank()) {
                problems.add("the source for '" + name + "' has no chunk identifier");
            }
        });

        if (card.confidence() == null) {
            problems.add("confidence is missing; give a number between 0 and 1");
        } else if (card.confidence() < 0.0 || card.confidence() > 1.0) {
            problems.add("confidence is " + card.confidence() + "; it must be between 0 and 1");
        }

        if (card.benefits().isEmpty()) {
            problems.add("there are no benefits");
        }

        return List.copyOf(problems);
    }

    /**
     * The draft as JSON text, for handing back to the model during a field repair.
     *
     * <p>Full shape rather than a summary: the model has to see the whole card to change one field
     * without disturbing the rest, and a summary would invite it to invent the parts it cannot see.
     */
    public static String describe(ProductCard card) {
        return String.format(
                "{\"title\":\"%s\",\"description\":\"%s\",\"characteristics\":%s,\"benefits\":%s,"
                        + "\"missingFields\":%s,\"confidence\":%s,\"sources\":%s}",
                card.title(), card.description(), asJsonObject(card.characteristics()),
                card.benefits(), card.missingFields(), card.confidence(), asJsonObject(card.sources()));
    }

    private static String asJsonObject(Map<String, String> values) {
        StringJoiner entries = new StringJoiner(", ");
        values.forEach((key, value) ->
                entries.add('"' + key.replace("\"", "\\\"") + "\": \"" + String.valueOf(value).replace("\"", "\\\"") + '"'));
        return "{" + entries + "}";
    }


    /**
     * A JSON schema derived from the record itself.
     *
     * <p>Derived rather than written by hand so it cannot drift from the type the application
     * parses into — a hand-written schema that disagrees with the record produces a model that is
     * right and an application that rejects it.
     *
     * <p>Sent as prompt text because local servers do not enforce a strict structured-output mode.
     * The assignment permits this fallback explicitly.
     */
    public static String schemaFor(Class<?> type) {
        StringBuilder json = new StringBuilder();
        json.append("{\n  \"type\": \"object\",\n  \"properties\": {");
        var components = type.getRecordComponents();
        for (int i = 0; i < components.length; i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append("\n    \"").append(components[i].getName()).append("\": ")
                    .append(typeOf(components[i].getType(), 2));
        }
        json.append("\n  },\n  \"required\": [");
        for (int i = 0; i < components.length; i++) {
            if (i > 0) {
                json.append(", ");
            }
            json.append('"').append(components[i].getName()).append('"');
        }
        json.append("]\n}");
        return json.toString();
    }

    private static String typeOf(Class<?> type, int depth) {
        if (type == String.class || type == Character.class || type == char.class) {
            return "{\"type\": \"string\"}";
        }
        if (type == Boolean.class || type == boolean.class) {
            return "{\"type\": \"boolean\"}";
        }
        if (type == Integer.class || type == int.class
                || type == Long.class || type == long.class) {
            return "{\"type\": \"integer\"}";
        }
        if (type == Double.class || type == double.class
                || type == Float.class || type == float.class
                || type == BigDecimal.class) {
            return "{\"type\": \"number\"}";
        }
        if (type.isEnum()) {
            // A verdict is a controlling signal, so the permitted values travel with the schema
            // rather than being described in prose. A model answering "ok" where "APPROVE" was
            // expected then fails the contract instead of quietly taking the wrong branch.
            StringJoiner values = new StringJoiner(", ");
            for (Object constant : type.getEnumConstants()) {
                values.add('"' + constant.toString() + '"');
            }
            return "{\"type\": \"string\", \"enum\": [" + values + "]}";
        }
        if (type == Map.class) {
            return "{\"type\": \"object\", \"additionalProperties\": {\"type\": \"string\"}}";
        }
        if (Collection.class.isAssignableFrom(type)) {
            return "{\"type\": \"array\", \"items\": {\"type\": \"string\"}}";
        }
        if (depth <= 0 || !type.isRecord()) {
            return "{\"type\": \"object\"}";
        }
        return schemaFor(type);
    }
}
