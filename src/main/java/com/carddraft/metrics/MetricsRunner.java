package com.carddraft.metrics;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.carddraft.agents.ProductCard;
import com.carddraft.agents.SupportJudgement;
import com.carddraft.context.AssembledContext;
import com.carddraft.context.CitationVerifier;
import com.carddraft.context.ContextChunk;
import com.carddraft.llm.JobLogContext;
import com.carddraft.repositories.ModelCallRepository;

/**
 * The command: generate cards for the reference set, measure them, write a report.
 *
 * <p>Opt-in through {@code card.metrics.enabled}, and not run by default, because a full run costs
 * several minutes of local model time and a service that measured itself on every restart would be
 * a service nobody could restart.
 *
 * <p>The default scope is the reference set's own declared default subset. A local model is slow
 * enough that measuring all six documents takes long enough to be skipped, and a harness that is
 * skipped measures nothing. So the cheap run is the default and the complete one is a flag.
 *
 * <p>Documents come from the golden set's own filenames rather than from a scan of {@code data/}:
 * the reference set says which documents it describes, and a harness that measured whatever happened
 * to be in the directory would quietly change its subject between runs and make two reports
 * incomparable.
 */
@Component
@ConditionalOnProperty(name = "card.metrics.enabled", havingValue = "true")
public class MetricsRunner implements ApplicationRunner {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MetricsRunner.class);

    private static final Path GOLDEN = Path.of("data", "golden_cards.json");

    /**
     * Inside the repository rather than under {@code build/}, because the acceptance step
     * requires the report to be an artefact of the run that stays: a file under a git-ignored
     * directory is a report nobody can review after the fact.
     */
    private static final Path OUTPUT = Path.of("docs", "metrics");

    private final CardGenerator cards;
    private final SupportJudge judge;
    private final MetricsReportWriter writer;
    private final MetricsProperties properties;
    private final ModelCallRepository calls;

    public MetricsRunner(CardGenerator cards, SupportJudge judge,
                         MetricsReportWriter writer, MetricsProperties properties,
                         ModelCallRepository calls) {
        this.cards = cards;
        this.judge = judge;
        this.writer = writer;
        this.properties = properties;
        this.calls = calls;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        Path report = run(properties.fullSet() ? "full" : "default");
        log.info("metrics_report_written path={}", report.toAbsolutePath());
    }

    /** Measures and writes, returning where the report went. */
    public Path run(String scope) throws IOException {
        ReferenceSet reference = ReferenceSet.load(GOLDEN);
        List<String> documents = "full".equals(scope) ? reference.documents() : reference.defaults();

        List<DocumentMetrics> results = new ArrayList<>();
        for (String document : documents) {
            results.add(measure(document, reference.characteristicsOf(document)));
        }

        MetricsReport report = new MetricsReport(
                "run-" + System.currentTimeMillis(),
                scope + " (" + documents.size() + " documents)",
                results,
                average(results, Metric.CHARACTERISTIC_MATCH),
                average(results, Metric.CITATION_PRECISION),
                average(results, Metric.SOURCE_SUPPORT));

        Path destination = OUTPUT.resolve(report.runId() + ".md");
        Files.createDirectories(OUTPUT);
        return writer.write(report, destination);
    }

    /**
     * One document: generate, then measure three separate things.
     *
     * <p>Measured against the reference rather than against what the card happened to say. A metric
     * scored on the card's own terms is satisfied by a short card: fewer claims, no misses.
     *
     * <p>A document whose model calls could not be satisfied is recorded as failed rather than
     * allowed to end the run. This method is an {@code ApplicationRunner}'s whole work, and one
     * unusable answer escaping it fails the application context — so a single document that
     * generates badly costs the measurements for every document that would have succeeded, which
     * is the exact inversion a metrics harness exists to avoid. The failure is still visible: it
     * appears in the report as a failed document carrying the reason.
     *
     * <p>Visible rather than private because that behaviour is invisible from outside the runner,
     * and a claim that cannot be read from a test is one that quietly stops being true.
     */
    DocumentMetrics measure(String document, Map<String, String> expected) {
        if (expected.isEmpty()) {
            return DocumentMetrics.failed(document, "the reference set records no characteristics");
        }

        AssembledContext context;
        ProductCard card;
        try {
            CardGenerator.Generated generated = cards.generate(document);
            context = generated.context();
            card = generated.card();
        } catch (RuntimeException e) {
            log.warn("metrics_document_failed document={} stage=generate error={}", document, e.toString());
            return DocumentMetrics.failed(document,
                    "no usable card could be generated: " + e.getMessage(), costOf(document));
        }

        if (context == null || context.isEmpty() || card == null) {
            return DocumentMetrics.failed(document, "no card could be generated from this document",
                    costOf(document));
        }

        List<String> missed = new ArrayList<>();
        int matched = 0;
        for (Map.Entry<String, String> characteristic : expected.entrySet()) {
            if (ValueNormaliser.matches(card.characteristics().get(characteristic.getKey()),
                    characteristic.getValue())) {
                matched++;
            } else {
                missed.add(characteristic.getKey() + " = "
                        + (card.characteristics().get(characteristic.getKey()) == null
                                ? "<absent>" : card.characteristics().get(characteristic.getKey()))
                        + ", expected " + characteristic.getValue());
            }
        }

        double match = expected.isEmpty() ? 0 : (double) matched / expected.size();

        List<ContextChunk> cited = new ArrayList<>();
        for (String reference : card.sources().values()) {
            context.find(CitationVerifier.canonicalReference(reference)).ifPresent(cited::add);
        }

        SupportJudgement judgement = judge.judge(card, cited);
        double precision = card.sources().isEmpty()
                ? 0
                : (double) countPreciseCitations(card, context) / card.sources().size();

        return new DocumentMetrics(document, match, precision, judgement.score(card.characteristics().size()), expected.size(),
                missed, judgement.unsupportedAmong(List.copyOf(card.characteristics().keySet())),
                judgement.measured() ? null : judgement.unavailableReason(), costOf(document));
    }

    /**
     * What one document's generation cost, read from the call records by job.
     *
     * <p>The harness generates under a job per document precisely so this question has an answer:
     * the same rows the service's own accounting is built from, summed for this run's job. The
     * query coalesces to zero in production; a stubbed repository in tests answers null, which
     * is normalised here rather than in the record — an unknown cost is a property of the test
     * double, not a value the report may print.
     */
    private BigDecimal costOf(String document) {
        BigDecimal cost = calls.costOfJob(CardGenerator.jobIdFor(document));
        return cost == null ? BigDecimal.ZERO : cost;
    }

    /**
     * How many of a card's citations name a fragment that really contains the cited value.
     *
     * <p>Citation precision, as the ticket defines it: a citation that names nothing is not a
     * source, and neither is a citation to a fragment that does not say what it is cited for.
     * It is measured mechanically rather than asked of a model, because the question is about
     * bookkeeping and a model that guesses here produces a number nobody can act on.
     *
     * <p>Counted per characteristic rather than per distinct label: twelve claims citing one
     * fragment that states all twelve are twelve precise citations. The comparison is normalised
     * but conservative — containment, not equality — because a fragment holds prose around the
     * value while the value itself must appear in it. A paraphrase does not count, which is the
     * direction a quality metric must err in.
     */
    private int countPreciseCitations(ProductCard card, AssembledContext context) {
        int precise = 0;
        for (Map.Entry<String, String> source : card.sources().entrySet()) {
            String value = card.characteristics().get(source.getKey());
            String expected = ValueNormaliser.normalise(value);
            if (expected.isEmpty()) {
                continue;
            }
            boolean contains = context.find(CitationVerifier.canonicalReference(source.getValue()))
                    .map(chunk -> ValueNormaliser.normalise(chunk.text()).contains(expected))
                    .orElse(false);
            if (contains) {
                precise++;
            }
        }
        return precise;
    }

    /**
     * The mean of one metric across the documents it was measured on.
     *
     * <p>Support averages over the documents whose judge answered and no others. Including a
     * document the judge never reached would drag the average down by a number that records the
     * harness's own failure rather than the card's quality — and a metric that falls because the
     * measurement broke is one nobody can act on.
     *
     * <p>Visible rather than private because that exclusion is a claim about what the report
     * asserts, and a claim that cannot be read from a test is one that quietly stops being true.
     */
    double average(List<DocumentMetrics> results, Metric metric) {
        List<DocumentMetrics> measured = metric == Metric.SOURCE_SUPPORT
                ? results.stream().filter(DocumentMetrics::supportMeasured).toList()
                : results;
        if (measured.isEmpty()) {
            return 0;
        }
        double total = 0;
        for (DocumentMetrics metrics : measured) {
            total += metric.valueOf(metrics);
        }
        return total / measured.size();
    }

    /** Whether to measure the whole reference set or its declared default. */
    @org.springframework.boot.context.properties.ConfigurationProperties("card.metrics")
    public record MetricsProperties(
            @org.springframework.boot.context.properties.bind.DefaultValue("false") boolean fullSet) {
    }
}