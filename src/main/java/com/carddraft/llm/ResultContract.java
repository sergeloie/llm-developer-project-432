package com.carddraft.llm;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

import com.carddraft.agents.CritiqueReport;
import com.carddraft.agents.ModelVerdict;
import com.carddraft.agents.ProductCard;
import com.carddraft.agents.ReviewIssue;
import com.carddraft.agents.SupportJudgement;

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
     * The same check and the same instruction for the injection judge's answer.
     *
     * <p>Only the reason is required, and that is the whole contract: a verdict without a sentence is
     * a verdict nobody can act on when a supplier asks why a fragment of their document was kept out
     * of the card. It matters more here than elsewhere in this class, because this is the one call
     * whose answer silently discards content — so a bare {@code false} with no reason is exactly the
     * shape that should be sent back rather than believed.
     */
    public static List<String> problemsWith(ModelVerdict verdict) {
        if (verdict.reason() == null || verdict.reason().isBlank()) {
            return List.of("the reason is empty; say in one short sentence why the fragment is or is "
                    + "not addressed to a language model");
        }
        return List.of();
    }

    /**
     * The support judge's answer, checked the same way.
     *
     * <p>Only a claim the judge rejected must carry its sentence, and that is the whole contract. A
     * claim it accepted needs no justification to be useful — the card passed and the report has
     * nothing to say about it. A rejected claim is exactly what the report lists by name and what a
     * person opens it to find out, so an unsupported verdict without a sentence is a line in the
     * report nobody can act on.
     *
     * <p>Asymmetric on purpose. Asking for a sentence on every claim would double the judge's output
     * for the majority of claims that pass, and spend tokens to protect a number that needs no
     * explanation.
     */
    public static List<String> problemsWith(SupportJudgement judgement) {
        List<String> problems = new java.util.ArrayList<>();
        for (Map.Entry<String, Boolean> claim : judgement.supported().entrySet()) {
            if (Boolean.TRUE.equals(claim.getValue())) {
                continue;
            }
            String reason = judgement.reasoning().get(claim.getKey());
            if (reason == null || reason.isBlank()) {
                problems.add("you marked \"" + claim.getKey() + "\" as unsupported without saying "
                        + "why; add one short sentence to reasoning naming what the fragment does say");
            }
        }
        return List.copyOf(problems);
    }

    /**
     * The reviewer's answer, checked the same way.
     *
     * <p>Only the sentence is required, because it is the whole of what the next attempt is shown:
     * an objection with a field and no sentence tells the generator which line to look at and not
     * what is wrong with it. The field is allowed to be blank, because an objection about the card as
     * a whole has no field, and refusing it would cost the round a real finding.
     */
    public static List<String> problemsWith(CritiqueReport report) {
        List<String> problems = new java.util.ArrayList<>();
        if (report == null) {
            return List.of("the review arrived empty; return a verdict of APPROVE or REJECT with issues");
        }
        if (report.verdict() == null) {
            problems.add("the verdict is missing; return APPROVE if the draft is acceptable, REJECT otherwise");
        }
        for (ReviewIssue issue : report.issues()) {
            if (issue.problem().isBlank()) {
                problems.add("an issue about \"" + (issue.field().isEmpty() ? "the card" : issue.field())
                        + "\" has no problem statement; say what is wrong in one sentence");
            }
        }
        return List.copyOf(problems);
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
            } else if (isBareReference(value)) {
                problems.add("the characteristic '" + name + "' has the reference '" + value.strip()
                        + "' as its value; put the fact from the fragment as the value "
                        + "and keep the reference in sources");
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
     * Whether a value is a citation label rather than a fact.
     *
     * <p>Observed live: a model that is told to cite every claim sometimes writes the label
     * where the fact goes ({@code "Power": "C1"}), producing a card that is valid JSON,
     * passes every other check, and scores zero on attribute match. Anchored to the whole
     * value, so a real value merely containing such text is untouched.
     */
    private static boolean isBareReference(String value) {
        return value.strip().matches("(?i)\\[?C\\d+]?");
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
