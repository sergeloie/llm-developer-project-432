package com.carddraft.metrics;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.carddraft.agents.ProductCard;
import com.carddraft.agents.SupportJudgement;
import com.carddraft.context.ContextChunk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

    // --- citation precision ------------------------------------------------------------

    /**
     * A citation to an existing fragment that does not state the cited value is not precise.
     *
     * <p>Existence alone would score a card that cites C2 for every claim while C2 says nothing
     * about any of them. The ticket defines precision as "the source fragment really does contain
     * the cited value", and the comparison is normalised but conservative: containment after
     * normalisation, so prose around the value is fine but a paraphrase is not a hit.
     */
    @Test
    void citationPrecisionRequiresTheFragmentToContainTheCitedValue() {
        CardGenerator cards = mock(CardGenerator.class);
        given(cards.generate(anyString())).willReturn(new CardGenerator.Generated(
                new com.carddraft.context.AssembledContext("probe", List.of(
                        new ContextChunk("C1", 1L, "doc", 1, "Power", "Power 800 W"),
                        new ContextChunk("C2", 2L, "doc", 1, "Weight", "Weight 5.9 kg")), 0, 0),
                new ProductCard("Kettle", "A 1.7 litre kettle.",
                        Map.of("Power", "800 W", "Volume", "1.5 l"),
                        List.of("fast"), List.of(), 0.9,
                        Map.of("Power", "C1", "Volume", "C2"))));

        SupportJudge judge = mock(SupportJudge.class);
        given(judge.judge(any(), any())).willReturn(new SupportJudgement(
                Map.of("Power", true, "Volume", true),
                Map.of("Power", "C1 states it", "Volume", "C2 states it")));

        MetricsRunner runner = new MetricsRunner(cards, judge,
                new MetricsReportWriter(), new MetricsRunner.MetricsProperties(false),
                mock(com.carddraft.repositories.ModelCallRepository.class));

        DocumentMetrics metrics = runner.measure("probe.pdf",
                Map.of("Power", "800 W", "Volume", "1.5 l"));

        assertThat(metrics.characteristicMatch()).isEqualTo(1.0);
        assertThat(metrics.citationPrecision())
                .as("C2 exists but says nothing about 1.5 l, so one of two citations is precise")
                .isEqualTo(0.5);
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
        SupportJudgement judgement = new SupportJudgement(Map.of("Power", true),
                Map.of("Power", "the fragment says 800 W"));

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
        SupportJudgement judgement = new SupportJudgement(Map.of(), Map.of());

        assertThat(judgement.score()).isZero();
        assertThat(judgement.unsupported()).isEmpty();
    }

    @Test
    void aFullySupportedCardScoresOne() {
        SupportJudgement judgement = new SupportJudgement(Map.of("Power", true, "Volume", true),
                Map.of("Power", "C1 states it", "Volume", "C2 states it"));

        assertThat(judgement.score()).isEqualTo(1.0);
        assertThat(judgement.unsupported()).isEmpty();
    }

    // --- a judge that could not be asked at all -------------------------------------------

    /**
     * A judge that could not be asked is not a card scoring zero.
     *
     * <p>Found by running {@code ./gradlew metrics -Pfull}, which ended in
     * {@code Error starting ApplicationContext} and wrote no report: the client could not read the
     * judge's object answer, nothing above it caught the failure, and {@code MetricsRunner} is an
     * {@code ApplicationRunner} — so one unusable answer cost the whole run.
     */
    @Test
    void aJudgeThatCannotBeAskedIsRecordedRatherThanThrown() {
        com.carddraft.llm.LlmClient client = mock(com.carddraft.llm.LlmClient.class);
        when(client.judgeSupport(anyString())).thenThrow(
                new com.carddraft.llm.ModelResponseFormatException(
                        "judgeSupport: still invalid after 2 repair attempts. Problems: cannot be read"));

        SupportJudgement judgement = new SupportJudge(client, new tools.jackson.databind.ObjectMapper())
                .judge(card(), cited());

        assertThat(judgement.measured())
                .as("a judge that never answered has not measured anything")
                .isFalse();
        assertThat(judgement.unavailableReason()).contains("cannot be read");
    }

    @Test
    void theRunFinishesAndSaysSupportWasNotMeasuredRatherThanPrintingZero() {
        com.carddraft.llm.LlmClient client = mock(com.carddraft.llm.LlmClient.class);
        when(client.judgeSupport(anyString())).thenThrow(
                new com.carddraft.llm.ModelResponseFormatException("unreadable"));

        CardGenerator cards = mock(CardGenerator.class);
        given(cards.generate(anyString())).willReturn(new CardGenerator.Generated(
                new com.carddraft.context.AssembledContext(
                        "probe", List.of(new ContextChunk("C1", 1L, "probe", 1, "Power", "Power 800 W")), 0, 0),
                card()));

        MetricsReport report = new MetricsReport("run-1", "default",
                List.of(new DocumentMetrics("a.pdf", 1.0, 1.0, 0, 2, List.of(), List.of(),
                        "the judge could not be asked", java.math.BigDecimal.ZERO)),
                1.0, 1.0, 0.0);

        String text = new MetricsReportWriter().render(report);

        assertThat(text)
                .as("0.000 here would be indistinguishable from a card whose claims were all "
                        + "found unsupported, which is the one reading this must not convey")
                .contains("not measured")
                .contains("support judge unavailable")
                .contains("the judge could not be asked");
    }

    /**
     * A document the judge never reached must not drag the support average down.
     *
     * <p>The average would then record the harness's own failure as though it were the cards'
     * quality, and a metric that falls because the measurement broke is one nobody can act on.
     */
    @Test
    void theSupportAverageSkipsDocumentsTheJudgeNeverReached() {
        List<DocumentMetrics> results = List.of(
                new DocumentMetrics("judged.pdf", 1.0, 1.0, 1.0, 2, List.of(), List.of()),
                new DocumentMetrics("unjudged.pdf", 1.0, 1.0, 0, 2, List.of(), List.of(), "no judge"));

        MetricsRunner runner = new MetricsRunner(mock(CardGenerator.class), mock(SupportJudge.class),
                new MetricsReportWriter(), new MetricsRunner.MetricsProperties(false),
                mock(com.carddraft.repositories.ModelCallRepository.class));

        assertThat(runner.average(results, Metric.SOURCE_SUPPORT))
                .as("0.5 would mean the cards were half unsupported, rather than that one was never judged")
                .isEqualTo(1.0);
        assertThat(runner.average(results, Metric.CHARACTERISTIC_MATCH))
                .as("the other two metrics were measured on every document and still average over all")
                .isEqualTo(1.0);
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

    /**
     * The metric set is defined once, and every name the harness prints or serialises comes from it.
     *
     * <p>Without this, adding or renaming a metric is a multi-file edit and a typo becomes a
     * silently-missing number: the average, the weakest summary, the per-document table and
     * {@code asMap} each carried their own copy of the names.
     */
    @Test
    void oneEnumDefinesEveryMetricNameAndValue() {
        DocumentMetrics metrics = new DocumentMetrics("a.pdf", 1.0, 0.5, 0.25, 4, List.of(), List.of());

        assertThat(Metric.CHARACTERISTIC_MATCH.valueOf(metrics)).isEqualTo(1.0);
        assertThat(Metric.CITATION_PRECISION.valueOf(metrics)).isEqualTo(0.5);
        assertThat(Metric.SOURCE_SUPPORT.valueOf(metrics)).isEqualTo(0.25);
        assertThat(metrics.asMap()).containsOnlyKeys(
                Metric.CHARACTERISTIC_MATCH.key(),
                Metric.CITATION_PRECISION.key(),
                Metric.SOURCE_SUPPORT.key());
    }

    @Test
    void theReportPrintsTheEnumsOwnNames() {
        MetricsReport report = new MetricsReport("run-1", "default",
                List.of(new DocumentMetrics("a.pdf", 1.0, 0.5, 0.25, 2, List.of(), List.of())),
                1.0, 0.5, 0.25);

        String text = new MetricsReportWriter().render(report);

        for (Metric metric : Metric.values()) {
            assertThat(text).contains(metric.label());
        }
    }

    /**
     * A tie has one answer, and every path gives it.
     *
     * <p>The document-level weakest metric and the report-level weakest summary used to walk the
     * same three numbers through different shapes — an equality chain and an indexed loop — so a tie
     * could resolve to different metrics depending on which path asked. Declaration order settles it.
     */
    @Test
    void tiedMetricsNameTheSameMetricInEveryPath() {
        DocumentMetrics tied = new DocumentMetrics("tie.pdf", 0.5, 0.5, 0.5, 2, List.of(), List.of());

        assertThat(tied.weakestMetric())
                .as("a three-way tie resolves to declaration order, not to an arbitrary path")
                .isEqualTo(Metric.CHARACTERISTIC_MATCH.label());

        MetricsReport report = new MetricsReport("run-1", "default", List.of(tied), 0.5, 0.5, 0.5);

        assertThat(report.weakestSummary())
                .as("the report resolves the same tie the document does")
                .contains("weakest metric: " + Metric.CHARACTERISTIC_MATCH.label());
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

    @Test
    void theReportShowsWhatEachDocumentCost() {
        MetricsReport report = new MetricsReport("run-1", "default",
                List.of(new DocumentMetrics("a.pdf", 1.0, 1.0, 0.9, 3, List.of(), List.of(),
                        null, new java.math.BigDecimal("0.0045"))),
                1.0, 1.0, 0.9);

        String text = new MetricsReportWriter().render(report);

        assertThat(text)
                .as("the report promises quality and cost, so both have to be in the file")
                .contains("## Cost")
                .contains("a.pdf")
                .contains("0.0045");
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
        assertThat(failed.supportUnavailableReason()).isEqualTo("no text layer");
        assertThat(failed.missedCharacteristics())
                .as("no card means no characteristic is missing from one, so the reason belongs to "
                        + "the document rather than to the match")
                .isEmpty();
        assertThat(failed.weakestMetric())
                .as("three zeros tie, and the first is reported rather than an arbitrary one")
                .isEqualTo("characteristic match");
    }

    /**
     * One document that could not be measured still belongs in the report.
     *
     * <p>Recorded as failed rather than allowed to escape, because {@code MetricsRunner} is an
     * {@code ApplicationRunner}: an exception here fails the application context, so a single
     * document whose model calls could not be satisfied would cost the measurements for every
     * document that would have succeeded.
     *
     * <p>Found by running {@code ./gradlew metrics -Pfull} after the support judge's shape was
     * fixed — the same escape, reached through {@code draftCardFromContext} instead.
     */
    @Test
    void aDocumentThatCannotBeGeneratedIsRecordedRatherThanEndingTheRun() {
        CardGenerator cards = mock(CardGenerator.class);
        when(cards.generate(anyString())).thenThrow(new com.carddraft.llm.ModelResponseFormatException(
                "still invalid after 2 repair attempts. Problems: sources names "
                        + "'Корпус не обжигает' but there is no such characteristic"));

        MetricsRunner runner = new MetricsRunner(cards, judge(),
                new MetricsReportWriter(), new MetricsRunner.MetricsProperties(false),
                mock(com.carddraft.repositories.ModelCallRepository.class));

        assertThat(runner.measure("kettle_manual.pdf", Map.of("Power", "2200 W")))
                .as("one document with an unusable model answer must not cost the run its report")
                .satisfies(metrics -> {
                    assertThat(metrics.supportMeasured())
                            .as("nothing was judged, so no support number is a measurement")
                            .isFalse();
                    assertThat(metrics.supportUnavailableReason())
                            .as("why the document produced nothing has to be readable, not just zero")
                            .contains("no usable card")
                            .contains("no such characteristic");
                    assertThat(metrics.missedCharacteristics())
                            .as("a document that never generated has no card for a characteristic "
                                    + "to be missing from, and listing the failure under both headings "
                                    + "makes the report contradict itself")
                            .isEmpty();
                });
    }

    @Test
    void aDocumentThatGeneratedNothingIsListedOnceRatherThanUnderTwoHeadings(@TempDir Path directory) {
        MetricsReport report = new MetricsReport("run-1", "full (1 document)",
                List.of(DocumentMetrics.failed("kettle_manual.pdf", "no usable card could be generated")),
                0, 0, 0);

        String text = new MetricsReportWriter().render(report);

        assertThat(text).contains("kettle_manual.pdf — support judge unavailable");
        assertThat(text)
                .as("the failure reason appears once, under the heading that owns it")
                .containsOnlyOnce("no usable card could be generated");
        assertThat(text)
                .as("and not also as an unmatched characteristic")
                .doesNotContain("not matched");
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