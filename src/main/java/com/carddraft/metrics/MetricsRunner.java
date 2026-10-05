package com.carddraft.metrics;

import java.io.IOException;
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
import com.carddraft.context.AssembledContext;
import com.carddraft.context.ContextChunk;
import com.carddraft.llm.JobLogContext;

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

    private static final Path GOLDEN = Path.of("data", "golden_cards.json");
    private static final Path OUTPUT = Path.of("build", "reports", "metrics");

    private final CardGenerator cards;
    private final SupportJudge judge;
    private final MetricsReportWriter writer;
    private final MetricsProperties properties;

    public MetricsRunner(CardGenerator cards, SupportJudge judge,
                         MetricsReportWriter writer, MetricsProperties properties) {
        this.cards = cards;
        this.judge = judge;
        this.writer = writer;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        Path report = run(properties.fullSet() ? "full" : "default");
        System.out.println("metrics report written to " + report.toAbsolutePath());
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
                average(results, 0),
                average(results, 1),
                average(results, 2));

        Path destination = OUTPUT.resolve(report.runId() + ".md");
        Files.createDirectories(OUTPUT);
        return writer.write(report, destination);
    }

    /**
     * One document: generate, then measure three separate things.
     *
     * <p>Measured against the reference rather than against what the card happened to say. A metric
     * scored on the card's own terms is satisfied by a short card: fewer claims, no misses.
     */
    private DocumentMetrics measure(String document, Map<String, String> expected) {
        if (expected.isEmpty()) {
            return DocumentMetrics.failed(document, "the reference set records no characteristics");
        }

        AssembledContext context = cards.generate(document);
        if (context == null || context.isEmpty()) {
            return DocumentMetrics.failed(document, "no card could be generated from this document");
        }

        ProductCard card = cards.lastCard();
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
            context.find(reference).ifPresent(cited::add);
        }

        SupportJudgement judgement = judge.judge(card, cited);
        double precision = card.sources().isEmpty()
                ? 0
                : (double) countRealCitations(cited, card.sources().size()) / card.sources().size();

        return new DocumentMetrics(document, match, precision, judgement.score(card.characteristics().size()), expected.size(),
                missed, judgement.unsupportedAmong(List.copyOf(card.characteristics().keySet())));
    }

    /**
     * How many of a card's citations name a fragment that exists.
     *
     * <p>Citation precision, as the ticket defines it: a citation that names nothing is not a
     * source. It is measured mechanically rather than asked of a model, because the question is
     * about bookkeeping and a model that guesses here produces a number nobody can act on.
     *
     * <p>Distinct labels are counted, so a card that cites C1 for four characteristics is one
     * good citation rather than four.
     */
    private int countRealCitations(List<ContextChunk> cited, int declared) {
        if (declared == 0) {
            return 0;
        }
        long distinct = cited.stream().map(ContextChunk::reference).distinct().count();
        return (int) Math.min(distinct, declared);
    }

    private double average(List<DocumentMetrics> results, int index) {
        if (results.isEmpty()) {
            return 0;
        }
        double total = 0;
        for (DocumentMetrics metrics : results) {
            total += switch (index) {
                case 0 -> metrics.characteristicMatch();
                case 1 -> metrics.citationPrecision();
                default -> metrics.sourceSupport();
            };
        }
        return total / results.size();
    }

    /** Whether to measure the whole reference set or its declared default. */
    @org.springframework.boot.context.properties.ConfigurationProperties("card.metrics")
    public record MetricsProperties(boolean fullSet) {

        public MetricsProperties {
            // Default false, so the cheap run is the one that happens.
        }
    }
}