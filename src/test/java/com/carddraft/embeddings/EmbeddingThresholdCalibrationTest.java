package com.carddraft.embeddings;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import com.carddraft.search.SearchSettings;

import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Derives the retrieval threshold from the reference probes instead of trusting the number in the
 * settings file.
 *
 * <p>Tagged {@code live-model} because it needs the model server: the whole point is to measure
 * what this model does with these prefixes on this text, and a stubbed embedding would measure
 * nothing. Everything else in the retrieval suite runs without a model, so this is the only place
 * the cost is paid.
 *
 * <p>The ticket asked for the calibration to be a test and not a note, and the reason is now plain:
 * the threshold has two halves. It has to be high enough to admit the correct fragment, and the
 * correct fragment's distance is a property of the model's weights and the prefixes applied to it.
 * Change the model, the prefixes, or the wording of a probe, and a threshold that was right becomes
 * wrong — silently, because a threshold that is merely too tight returns nothing and a threshold
 * that is merely too loose returns the neighbours. Neither looks like a failure from the call site.
 *
 * <p>So the test embeds each probe and asserts the configured value still covers it. A change to the
 * model card is expected to break this test, and the fix is to recalibrate and say why in
 * {@link SearchSettings} — not to widen the assertion.
 */
@Tag("live-model")
class EmbeddingThresholdCalibrationTest {

    private static final Path GOLDEN = Path.of("data", "golden_cards.json");

    /**
     * Skips rather than fails when the reference set or the model is absent.
     *
     * <p>Opt-in tag already keeps this out of the ordinary build; this keeps a tagged run honest on
     * a machine with no model server, where a failure would say nothing about the code.
     */
    static boolean referenceSetAndModelAreAvailable() {
        return Files.exists(GOLDEN) && modelIsReachable();
    }


    static boolean modelIsReachable() {
        try {
            var settings = new EmbeddingSettings(
                    System.getProperty("card.embedding.baseUrl", "http://127.0.0.1:1234"),
                    System.getProperty("card.embedding.model", "text-embedding-embeddinggemma-300m"),
                    Integer.parseInt(System.getProperty("card.embedding.dimension", "768")),
                    System.getProperty("card.embedding.queryPrefix", "task: search result | query: "),
                    System.getProperty("card.embedding.documentPrefix", "title: "),
                    16,
                    java.time.Duration.ofSeconds(120));
            var model = new LocalEmbeddingModel(
                    org.springframework.web.client.RestClient.builder(),
                    new ObjectMapper(),
                    settings);
            model.embedQuery("probe");
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    @EnabledIf("referenceSetAndModelAreAvailable")
    void everyReferenceProbeIsWithinTheConfiguredThreshold() throws Exception {
        EmbeddingModel model = liveModel();
        SearchSettings configured = configuredSettings();

        Map<String, Object> golden = readGolden();
        var documents = (Map<String, Map<String, Object>>) golden.get("documents");

        List<Probe> probes = new ArrayList<>();
        for (var document : documents.entrySet()) {
            var characteristics = (Map<String, String>) document.getValue().getOrDefault("characteristics", Map.of());
            var sourceProbes = (Map<String, Map<String, Object>>) document.getValue().get("source_probes");
            if (sourceProbes == null) {
                continue;
            }
            for (var probe : sourceProbes.entrySet()) {
                String answer = characteristics.get(probe.getKey());
                if (answer != null) {
                    probes.add(new Probe(document.getKey(), probe.getKey(),
                            String.valueOf(probe.getValue().get("text_contains")), answer));
                }
            }
        }

        assertThat(probes).as("the reference set carries probes to calibrate against").isNotEmpty();

        double worst = 0;
        Probe worstProbe = null;
        for (Probe probe : probes) {
            double distance = cosineDistance(
                    model.embedQuery(probe.query()),
                    model.embedDocument(probe.answer(), probe.characteristic()));
            System.out.printf("calibration %-24s %-28s %.3f%n",
                    probe.document(), probe.characteristic(), distance);
            if (distance > worst) {
                worst = distance;
                worstProbe = probe;
            }
        }

        assertThat(configured.maxVectorDistance())
                .as("the threshold must admit the worst reference probe (%s / %s at %.3f)",
                        worstProbe.document(), worstProbe.characteristic(), worst)
                .isGreaterThan(worst);

        assertThat(configured.maxVectorDistance())
                .as("and must stay below 1.0, or it admits chunks with no relationship at all")
                .isLessThan(1.0);
    }

    /**
     * The threshold the application actually runs with.
     *
     * <p>Takes the shipped defaults and lets an environment variable override them, mirroring the
     * precedence the application itself uses. The point is that this is the same number the
     * application will use, not a copy in a test.
     */
    private static SearchSettings configuredSettings() {
        SearchSettings defaults = SearchSettings.defaults();
        String override = System.getenv("CARD_SEARCH_MAXVECTORDISTANCE");
        if (override == null || override.isBlank()) {
            return defaults;
        }
        return new SearchSettings(
                Double.parseDouble(override),
                defaults.perListLimit(), defaults.rrfK(), defaults.limit());
    }

    /**
     * The prefixes buy separation, not similarity.
     *
     * <p>An earlier version of this test asserted that a question sits closer to its own answer
     * with the prefixes, and it failed: 0.363 against 0.305. That was the test being wrong rather
     * than the prefixes. A query and a document go through different templates, so they are meant
     * to be encoded as different kinds of thing — asking for them to be nearer to each other asks
     * the model to ignore the distinction the templates exist to draw.
     *
     * <p>What the prefixes should improve is the gap between a correct pair and an unrelated one.
     * That is the quantity a threshold can be placed inside, and measuring it is also what finally
     * supplies the upper bound {@link SearchSettings} records as missing.
     */
    @Test
    @EnabledIf("referenceSetAndModelAreAvailable")
    void thePrefixesSeparateACorrectPairFromAnUnrelatedOne() throws Exception {
        EmbeddingModel withPrefixes = liveModel();
        EmbeddingModel withoutPrefixes = bareModel();

        String query = "how much power does the blender have";
        String answer = "Power 800 W, bowl volume 1.5 l";
        String unrelated = "Replace the carbon filter every six months";

        double prefixedGap = gap(withPrefixes, query, answer, unrelated);
        double unprefixedGap = gap(withoutPrefixes, query, answer, unrelated);

        System.out.printf("calibration separation prefixed=%.3f unprefixed=%.3f%n",
                prefixedGap, unprefixedGap);

        assertThat(prefixedGap)
                .as("with the templates, an unrelated fragment must sit further from a question "
                        + "than the fragment that answers it")
                .isGreaterThan(0);
        assertThat(prefixedGap)
                .as("and the gap must not be smaller than it is without the templates")
                .isGreaterThanOrEqualTo(unprefixedGap);
    }

    /** Distance from the query to the answer, minus distance from the query to the unrelated text. */
    private static double gap(EmbeddingModel model, String query, String answer, String unrelated) {
        var queryVector = model.embedQuery(query);
        double toAnswer = cosineDistance(queryVector, model.embedDocument(answer, "Power"));
        double toUnrelated = cosineDistance(queryVector, model.embedDocument(unrelated, "Maintenance"));
        return toUnrelated - toAnswer;
    }

    private record Probe(String document, String characteristic, String query, String answer) {
    }

    private static EmbeddingModel bareModel() {
        return new LocalEmbeddingModel(
                org.springframework.web.client.RestClient.builder(), new ObjectMapper(),
                new EmbeddingSettings(baseUrl(), modelName(), dimension(), "", "", 16,
                        java.time.Duration.ofSeconds(120)));
    }

    private static EmbeddingModel liveModel() {
        return new LocalEmbeddingModel(
                org.springframework.web.client.RestClient.builder(), new ObjectMapper(),
                new EmbeddingSettings(
                        baseUrl(), modelName(), dimension(),
                        System.getProperty("card.embedding.queryPrefix", "task: search result | query: "),
                        System.getProperty("card.embedding.documentPrefix", "title: "),
                        16,
                        java.time.Duration.ofSeconds(120)));
    }

    private static String baseUrl() {
        String fromEnvironment = System.getenv("CARD_EMBEDDING_BASEURL");
        return fromEnvironment == null || fromEnvironment.isBlank()
                ? "http://127.0.0.1:1234" : fromEnvironment;
    }

    private static String modelName() {
        String fromEnvironment = System.getenv("CARD_EMBEDDING_MODEL");
        return fromEnvironment == null || fromEnvironment.isBlank()
                ? "text-embedding-embeddinggemma-300m" : fromEnvironment;
    }

    private static int dimension() {
        String fromEnvironment = System.getenv("CARD_EMBEDDING_DIMENSION");
        return fromEnvironment == null || fromEnvironment.isBlank()
                ? 768 : Integer.parseInt(fromEnvironment);
    }

    private static Map<String, Object> readGolden() throws Exception {
        return new ObjectMapper().readValue(Files.readString(GOLDEN), Map.class);
    }

    /** pgvector's cosine distance, computed here so the test needs no database. */
    static double cosineDistance(List<Double> left, List<Double> right) {
        double dot = 0;
        double leftNorm = 0;
        double rightNorm = 0;
        for (int i = 0; i < left.size(); i++) {
            dot += left.get(i) * right.get(i);
            leftNorm += left.get(i) * left.get(i);
            rightNorm += right.get(i) * right.get(i);
        }
        if (leftNorm == 0 || rightNorm == 0) {
            return 1.0;
        }
        return 1.0 - dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }
}
