package com.carddraft.metrics;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.carddraft.agents.ProductCard;
import com.carddraft.context.ContextChunk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * The harness's own arithmetic, which is the part that decides what the numbers mean.
 *
 * <p>No model and no database: both are exercised by the run itself, and a test that waited on a
 * local model would tell us whether the model was up rather than whether the metric is right.
 */
class MetricsHarnessTest {

    private static final String DATA = "data";

    // --- normalisation, which the first metric is built on ------------------------------

    @Test
    void spellingDifferencesDoNotCountAsDifferentValues() {
        assertThat(ValueNormaliser.matches("800 W", "800 W")).isTrue();
        assertThat(ValueNormaliser.matches("800 w", "800 W")).isTrue();
        assertThat(ValueNormaliser.matches("800 W.", "800 W")).isTrue();
        assertThat(ValueNormaliser.matches("  800   W  ", "800 W")).isTrue();
        assertThat(ValueNormaliser.matches("800 Вт", "800 Вт")).isTrue();
    }

    /**
     * A comma decimal separator is a habit, not a different value.
     *
     * <p>Both spellings appear in the reference set, so a harness that did not fold them would
     * report a miss for a card that is exactly right.
     */
    @Test
    void aCommaDecimalSeparatorIsTheSameNumber() {
        assertThat(ValueNormaliser.matches("1,5 л", "1.5 л")).isTrue();
        assertThat(ValueNormaliser.matches("1.5 л", "1,5 л")).isTrue();
    }

    /**
     * A comma between list items must survive.
     *
     * <p>"1,5 л, 0,9 кг" holds two facts. Folding every comma to a point would make it one token
     * matching nothing, and the reference value would stop being findable in the card at all.
     */
    @Test
    void aCommaSeparatingListItemsIsNotADecimalPoint() {
        assertThat(ValueNormaliser.matches("1,5 л, 0,9 кг", "1,5 л, 0,9 кг")).isTrue();
        assertThat(ValueNormaliser.normalise("1,5 л, 0,9 кг"))
                .as("only a comma between digits becomes a point")
                .isEqualTo("1.5 л, 0.9 кг");
    }

    /**
     * A value the reference does not know is a miss, never a hit.
     *
     * <p>The tempting alternative is to score only on what both sides mention, which would let a
     * card claim three safe things out of ten and score perfectly.
     */
    @Test
    void anAbsentReferenceNeverMatches() {
        assertThat(ValueNormaliser.matches("", "")).isFalse();
        assertThat(ValueNormaliser.matches("anything", null)).isFalse();
        assertThat(ValueNormaliser.matches(null, "800 W")).isFalse();
    }

    @Test
    void genuinelyDifferentValuesDoNotMatch() {
        assertThat(ValueNormaliser.matches("800 W", "900 W")).isFalse();
        assertThat(ValueNormaliser.matches("1.5 л", "1.7 л")).isFalse();
    }

    /**
     * Units are not bridged, on purpose.
     *
     * <p>Normalising "800 W" to "0.8 kW" would make a wrong card look right, and that is the one
     * direction a quality metric must never err in: a metric that rewards a lucky conversion is
     * worse than no metric, because it is believed.
     */
    @Test
    void unitsAreNotConverted() {
        assertThat(ValueNormaliser.matches("0.8 kW", "800 W")).isFalse();
    }

    // --- the judge prompt ----------------------------------------------------------------

    @Test
    void theJudgeIsGivenTheCardAndOnlyTheFragmentsItCites() {
        String prompt = judge().prompt(card(), cited());

        assertThat(prompt)
                .contains("[C1] Power — Power 800 W")
                .contains("[C2] Weight — Weight 5.9 kg")
                .contains("\"title\":\"Kettle\"");
        assertThat(prompt)
                .as("the judge must be told that formatting differences are the same value")
                .contains("800 W\", \"800 w\" and \"800 W.\" are the same value");
        assertThat(prompt)
                .as("and that a correct value under the wrong citation is not supported")
                .contains("is NOT supported");
    }

    /**
     * A judge that skipped a claim has not checked it.
     *
     * <p>Defaulting to supported would be the flattering reading and the wrong one: it would let a
     * broken judge report a perfect score, which is the failure a metrics harness must never have.
     */
    @Test
    void aClaimTheJudgeOmittedCountsAsUnsupported() {
        SupportJudgement judgement = new SupportJudge(mock(com.carddraft.llm.LlmClient.class),
                new tools.jackson.databind.ObjectMapper()).parseAnswer("""
                {"supported":{"Power":true},"reasoning":{"Power":"the fragment says 800 W"}}
                """);

        assertThat(judgement.score(2))
                .as("the denominator is what was asked about, not what the judge chose to answer - "
                        + "otherwise a lazy judge scores perfectly")
                .isEqualTo(0.5);
        assertThat(judgement.unsupportedAmong(List.of("Power", "Volume")))
                .as("an omission is listed exactly like an explicit false")
                .containsExactly("Volume");
    }

    @Test
    void aJudgeThatAnsweredNothingScoresZeroRatherThanOne() {
        SupportJudgement judgement = new SupportJudge(mock(com.carddraft.llm.LlmClient.class),
                new tools.jackson.databind.ObjectMapper()).parseAnswer("{}");

        assertThat(judgement.score()).isZero();
        assertThat(judgement.unsupported()).isEmpty();
    }

    @Test
    void aFullySupportedCardScoresOne() {
        SupportJudgement judgement = new SupportJudge(mock(com.carddraft.llm.LlmClient.class),
                new tools.jackson.databind.ObjectMapper()).parseAnswer("""
                {"supported":{"Power":true,"Volume":true}}
                """);

        assertThat(judgement.score()).isEqualTo(1.0);
        assertThat(judgement.unsupported()).isEmpty();
    }

    // --- the report ----------------------------------------------------------------------

    @Test
    void theReportNamesItsWeakestMetricAndWhereItCameFrom() {
        MetricsReport report = new MetricsReport("run-1", "default (2 documents)",
                List.of(
                        new DocumentMetrics("a.pdf", 1.0, 1.0, 0.9, 4, List.of(), List.of()),
                        new DocumentMetrics("b.pdf", 0.25, 0.5, 0.8, 4, List.of("power"), List.of())),
                0.625, 0.75, 0.85);

        assertThat(report.weakestSummary())
                .contains("weakest metric: characteristic match")
                .contains("0.625")
                .contains("worst document b.pdf")
                .contains("0.250");
    }

    @Test
    void anEmptyReportSaysSoRatherThanNamingAMetric() {
        assertThat(new MetricsReport("run-1", "default", List.of(), 0, 0, 0).weakestSummary())
                .isEqualTo("nothing was measured");
    }

    @Test
    void theReportSaysWhichNumberIsAnEstimate(@TempDir Path directory) throws Exception {
        MetricsReport report = new MetricsReport("run-1", "default",
                List.of(new DocumentMetrics("a.pdf", 1.0, 1.0, 0.9, 3, List.of(), List.of())),
                1.0, 1.0, 0.9);

        Path written = new MetricsReportWriter().write(report, directory.resolve("out/metrics.md"));
        String text = Files.readString(written, StandardCharsets.UTF_8);

        assertThat(text)
                .contains("Source support is a model's estimate, not a measurement")
                .contains("| source support (estimate) | 0.900 |")
                .contains("| characteristic match | 1.000 |")
                .contains("run-1")
                .contains("default");
    }

    @Test
    void theReportListsWhatWasMissedSoALowScoreCanBeRead(@TempDir Path directory) throws Exception {
        MetricsReport report = new MetricsReport("run-1", "default",
                List.of(new DocumentMetrics("a.pdf", 0.5, 1.0, 1.0, 2,
                        List.of("power = 900 W, expected 800 W"),
                        List.of("volume"))),
                0.5, 1.0, 1.0);

        String text = new MetricsReportWriter().render(report);

        assertThat(text)
                .contains("not matched")
                .contains("power = 900 W, expected 800 W")
                .contains("unsupported by their source")
                .contains("- volume");
    }

    // --- the reference set ---------------------------------------------------------------

    @Test
    void theReferenceSetComesFromTheGoldenFileAndNotFromACopyInCode() throws java.io.IOException {
        ReferenceSet reference = ReferenceSet.load(Path.of(DATA, "golden_cards.json"));

        assertThat(reference.documents())
                .contains("blender_passport.pdf", "blender_kp.docx", "kettle_spec.xlsx",
                        "kettle_manual.pdf", "boiler_scan.pdf");
        assertThat(reference.defaults())
                .as("the set declares its own cheap subset, and the harness uses that")
                .containsExactly("blender_passport.pdf", "kettle_manual.pdf");
        assertThat(reference.characteristicsOf("blender_passport.pdf")).isNotEmpty();
        assertThat(reference.characteristicsOf("blender_kp.docx"))
                .as("a document whose characteristics are keyed by article number")
                .containsKeys("BLD-800");
    }

    @Test
    void aDocumentTheSetSaysNothingAboutMeasuresAsEmptyRatherThanFailing() throws java.io.IOException {
        ReferenceSet reference = ReferenceSet.load(Path.of(DATA, "golden_cards.json"));

        assertThat(reference.characteristicsOf("not-in-the-set.pdf")).isEmpty();
    }

    @Test
    void aDocumentWithNoUsableCardIsRecordedAsFailedRatherThanScoringZero() {
        DocumentMetrics failed = DocumentMetrics.failed("boiler_scan.pdf", "no text layer");

        assertThat(failed.characteristicMatch()).isZero();
        assertThat(failed.characteristicTotal()).isZero();
        assertThat(failed.missedCharacteristics()).containsExactly("no text layer");
        assertThat(failed.weakestMetric())
                .as("three zeros tie, and the first is reported rather than an arbitrary one")
                .isEqualTo("characteristic match");
    }

    private static SupportJudge judge() {
        return new SupportJudge(mock(com.carddraft.llm.LlmClient.class),
                new tools.jackson.databind.ObjectMapper());
    }

    private static ProductCard card() {
        return new ProductCard("Kettle", "A 1.7 litre kettle.",
                Map.of("Power", "800 W", "Volume", "1.5 л"),
                List.of("fast"), List.of(), 0.9,
                Map.of("Power", "C1", "Volume", "C2"));
    }

    private static List<ContextChunk> cited() {
        return List.of(
                new ContextChunk("C1", 1L, "doc", 1, "Power", "Power 800 W"),
                new ContextChunk("C2", 2L, "doc", 1, "Weight", "Weight 5.9 kg"),
                new ContextChunk("C3", 3L, "doc", 2, "Care", "Descale monthly"));
    }
}