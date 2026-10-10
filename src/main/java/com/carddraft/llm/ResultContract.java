package com.carddraft.llm;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import com.carddraft.agents.CritiqueReport;
import com.carddraft.agents.ModelVerdict;
import com.carddraft.agents.ProductCard;
import com.carddraft.agents.SupportJudgement;

/**
 * Checks a result against the contract and says what is wrong in words the model can act on.
 *
 * <p>One place knows the rules, and it is the place that phrases them. A validator that returns
 * "constraint violated" forces a second translation step before the message can be sent back, and
 * the two drift. Here the same sentence is both the check and the instruction.
 */
public final class ResultContract {

    private ResultContract() {}

    /**
     * The same check and the same instruction for the injection judge's answer.
     *
     * <p>Both fields are required, and that is the whole contract. An omitted {@code suspicious}
     * must not deserialize to "not suspicious": a gate that opens when the model stays silent is a
     * gate that opens on every outage, which is the one direction this service must not fail. An
     * omission instead becomes a repairable message, exactly like an empty reason. The reason is
     * what a person reads when a supplier asks why a fragment of their document was kept out of the
     * card, and a bare {@code false} with no reason is exactly the shape that should be sent back
     * rather than believed.
     */
    public static List<String> problemsWith(ModelVerdict verdict) {
        List<String> problems = new java.util.ArrayList<>();
        if (verdict.suspicious() == null) {
            problems.add("suspicious is missing; say whether the fragment is addressed to a " + "language model");
        }
        if (verdict.reason() == null || verdict.reason().isBlank()) {
            problems.add("the reason is empty; say in one short sentence why the fragment is or is "
                    + "not addressed to a language model");
        }
        return List.copyOf(problems);
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
        report.issues().stream()
                .filter(issue -> issue.problem().isBlank())
                .map(issue -> "an issue about \"" + (issue.field().isEmpty() ? "the card" : issue.field())
                        + "\" has no problem statement; say what is wrong in one sentence")
                .forEach(problems::add);
        return List.copyOf(problems);
    }

    /**
     * @return the problems, empty when the result satisfies the contract. An empty list means
     *         usable; a non-empty one means send it back with these sentences attached.
     */
    public static List<String> problemsWith(ProductCard card) {
        List<String> problems = new java.util.ArrayList<>(problemsForGeneration(card));
        titleLengthProblem(card).ifPresent(problems::add);
        problems.addAll(sourcesProblems(card));
        return List.copyOf(problems);
    }

    /**
     * The contract a first generation must satisfy.
     *
     * <p>Everything except a long title and a bad sources mapping. Those two are repaired
     * pointwise afterwards — one field re-asked with the draft attached — so they are not reasons
     * to regenerate the whole card here. Everything else still is: an empty title cannot be
     * shortened into existence, and a missing description cannot be repaired without writing one.
     */
    static List<String> problemsForGeneration(ProductCard card) {
        List<String> problems = new java.util.ArrayList<>();

        if (card.title() == null || card.title().isBlank()) {
            problems.add("the title is empty");
        }

        if (card.description() == null || card.description().isBlank()) {
            problems.add("the description is empty");
        }

        card.characteristics().forEach((name, value) -> {
            if (value == null || value.isBlank()) {
                problems.add("the characteristic '" + name + "' has an empty value; "
                        + "either give it a value or move it to missingFields");
            } else if (isReference(value)) {
                problems.add("the characteristic '" + name + "' has the reference '" + value.strip()
                        + "' as its value; put the fact from the fragment as the value "
                        + "and keep the reference in sources");
            }
        });

        card.missingFields().stream()
                .filter(missing -> card.characteristics().containsKey(missing))
                .map(missing -> "'" + missing + "' appears in both characteristics and missingFields; "
                        + "a field cannot be present and absent at once")
                .forEach(problems::add);

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
     * A long title, as a sentence the model can act on, or nothing when the title fits.
     *
     * <p>Separate because a long title is repaired pointwise — re-asked alone with the draft
     * attached — rather than by regenerating the card. An empty title stays in the generation
     * contract: there is nothing to shorten.
     */
    public static java.util.Optional<String> titleLengthProblem(ProductCard card) {
        if (card.title() != null && !card.title().isBlank() && card.title().length() > ProductCard.MAX_TITLE_LENGTH) {
            return java.util.Optional.of("the title is " + card.title().length()
                    + " characters long; it must be at most " + ProductCard.MAX_TITLE_LENGTH
                    + ". Shorten it.");
        }
        return java.util.Optional.empty();
    }

    /**
     * The sources mapping, checked on its own.
     *
     * <p>Separate for the same reason as the title: a bad mapping is repaired pointwise. A value
     * must be a fragment reference in the shape the prompts define — the label exactly as shown
     * in brackets. A sentence explaining where the value came from is not a reference, even when
     * every word of it is true: the verifier resolves labels, not prose.
     */
    public static List<String> sourcesProblems(ProductCard card) {
        List<String> problems = new java.util.ArrayList<>();
        card.sources().forEach((name, chunkId) -> {
            if (!card.characteristics().containsKey(name)) {
                problems.add("sources names '" + name + "' but there is no such characteristic");
            }
            if (chunkId == null || chunkId.isBlank()) {
                problems.add("the source for '" + name + "' has no chunk identifier");
            } else if (!isExactReference(chunkId)) {
                problems.add("the source for '" + name + "' is '" + chunkId.strip() + "', which is "
                        + "not a fragment reference; use the label exactly as shown in brackets, "
                        + "such as C3");
            }
        });
        return List.copyOf(problems);
    }

    /**
     * A fragment reference in the shape the prompts define: {@code [C3]} or {@code C3}.
     *
     * <p>What the shape is and what is forgiven are two separate questions, and the sources gate
     * and the characteristic-value check answer them differently.
     *
     * <p>The sources gate answers the shape question only, and exactly: the verifier unwraps a
     * full pair of brackets and nothing else — case and surrounding space are the model's to get
     * right — so {@link #sourcesProblems} must accept the same forms the verifier resolves or a
     * card passes one gate and fails the next. That exactness is
     * {@link com.carddraft.context.CitationVerifierTest}, which locks the two together.
     *
     * <p>The characteristic-value check answers a different question — "is this value a reference
     * rather than a fact?" — and for that, a value the model padded with a stray space is still
     * clearly the mistake it is, so it is worth sending back even though the sources gate would
     * phrase the same padding differently.
     */
    private static final Pattern FRAGMENT_REFERENCE = Pattern.compile("(?:\\[C\\d+\\]|C\\d+)");

    /**
     * Whether a sources value is a reference exactly as the citation verifier will resolve it.
     *
     * <p>No stripping: {@code " C3 "} is what the model wrote, and the verifier looks that up as a
     * label the context does not have. See {@link #FRAGMENT_REFERENCE} for why the two checks here
     * are not the same predicate.
     */
    static boolean isExactReference(String value) {
        return FRAGMENT_REFERENCE.matcher(value).matches();
    }

    /**
     * Whether a value is a fragment reference rather than prose, forgiving surrounding space.
     *
     * <p>Anchored to the whole value, so a real value merely containing such text is untouched.
     * Observed live in both directions: a model that is told to cite every claim sometimes writes
     * the label where the fact goes ({@code "Power": "C1"}), and sometimes writes a sentence where
     * the label goes — a card that is valid JSON, passes every other check, and scores zero on
     * attribute match.
     *
     * <p>Only used for the characteristic-value check, never for the sources gate; see
     * {@link #FRAGMENT_REFERENCE}.
     */
    static boolean isReference(String value) {
        return FRAGMENT_REFERENCE.matcher(value.strip()).matches();
    }

    private static final Map<Class<?>, String> SCHEMAS = new ConcurrentHashMap<>();

    /**
     * A JSON schema derived from the record itself.
     *
     * <p>Derived rather than written by hand so it cannot drift from the type the application
     * parses into — a hand-written schema that disagrees with the record produces a model that is
     * right and an application that rejects it.
     *
     * <p>Sent as prompt text because local servers do not enforce a strict structured-output mode.
     * The assignment permits this fallback explicitly.
     *
     * <p>Built once per type and reused, because a prompt is rebuilt on every repair round and the
     * schema is a pure function of the type. Only the full-depth schema is cached; the recursion
     * inside works from the depth argument and never re-enters here.
     */
    public static String schemaFor(Class<?> type) {
        return SCHEMAS.computeIfAbsent(type, t -> schemaFor(t, 2));
    }

    private static String schemaFor(Class<?> type, int depth) {
        StringBuilder json = new StringBuilder();
        json.append("{\n  \"type\": \"object\",\n  \"properties\": {");
        var components = type.getRecordComponents();
        for (int i = 0; i < components.length; i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append("\n    \"")
                    .append(components[i].getName())
                    .append("\": ")
                    .append(typeOf(components[i].getGenericType(), depth));
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

    /**
     * The schema for one component's type, recursing through the generic arguments a {@code Class}
     * erases.
     *
     * <p>{@link java.lang.reflect.RecordComponent#getType()} returns the erased class, so
     * {@code List<ReviewIssue>} arrives as {@code List} and every collection would be declared an
     * array of strings — which is exactly the wrong thing for a list of records. The generic type
     * keeps the component, and the depth budget is spent going down so a shape that nests deeper
     * than it was given falls back to a plain object instead of recursing without limit.
     */
    private static String typeOf(Type type, int depth) {
        Class<?> raw = rawTypeOf(type);
        if (raw == String.class || raw == Character.class || raw == char.class) {
            return "{\"type\": \"string\"}";
        }
        if (raw == Boolean.class || raw == boolean.class) {
            return "{\"type\": \"boolean\"}";
        }
        if (raw == Integer.class || raw == int.class || raw == Long.class || raw == long.class) {
            return "{\"type\": \"integer\"}";
        }
        if (raw == Double.class
                || raw == double.class
                || raw == Float.class
                || raw == float.class
                || raw == BigDecimal.class) {
            return "{\"type\": \"number\"}";
        }
        if (raw.isEnum()) {
            // A verdict is a controlling signal, so the permitted values travel with the schema
            // rather than being described in prose. A model answering "ok" where "APPROVE" was
            // expected then fails the contract instead of quietly taking the wrong branch.
            StringJoiner values = new StringJoiner(", ");
            for (Object constant : raw.getEnumConstants()) {
                values.add('"' + constant.toString() + '"');
            }
            return "{\"type\": \"string\", \"enum\": [" + values + "]}";
        }
        if (raw == Map.class) {
            return "{\"type\": \"object\", \"additionalProperties\": {\"type\": \"string\"}}";
        }
        if (Collection.class.isAssignableFrom(raw)) {
            return "{\"type\": \"array\", \"items\": " + typeOf(elementTypeOf(type), depth - 1) + "}";
        }
        if (depth <= 0 || !raw.isRecord()) {
            return "{\"type\": \"object\"}";
        }
        return schemaFor(raw, depth - 1);
    }

    private static Class<?> rawTypeOf(Type type) {
        if (type instanceof Class<?> clazz) {
            return clazz;
        }
        if (type instanceof ParameterizedType parameterized) {
            return (Class<?>) parameterized.getRawType();
        }
        return Object.class;
    }

    private static Type elementTypeOf(Type type) {
        if (type instanceof ParameterizedType parameterized) {
            Type[] arguments = parameterized.getActualTypeArguments();
            if (arguments.length == 1) {
                return arguments[0];
            }
        }
        return Object.class;
    }
}
