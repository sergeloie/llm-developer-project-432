package com.carddraft.metrics;

import java.util.function.ToDoubleFunction;

/**
 * The metric set, defined once.
 *
 * <p>The key the harness serialises, the name it prints, the value it reads off a document and the
 * value it reads off a run's averages all live here, so adding or renaming a metric is one edit and
 * a typo cannot become a silently-missing number. Declaration order settles ties: the first metric
 * at the minimum is the weakest.
 */
enum Metric {
    CHARACTERISTIC_MATCH(
            "characteristicMatch",
            "characteristic match",
            DocumentMetrics::characteristicMatch,
            MetricsReport::averageCharacteristicMatch),
    CITATION_PRECISION(
            "citationPrecision",
            "citation precision",
            DocumentMetrics::citationPrecision,
            MetricsReport::averageCitationPrecision),
    SOURCE_SUPPORT(
            "sourceSupport", "source support", DocumentMetrics::sourceSupport, MetricsReport::averageSourceSupport);

    private final String key;
    private final String label;
    private final ToDoubleFunction<DocumentMetrics> documentValue;
    private final ToDoubleFunction<MetricsReport> averageValue;

    Metric(
            String key,
            String label,
            ToDoubleFunction<DocumentMetrics> documentValue,
            ToDoubleFunction<MetricsReport> averageValue) {
        this.key = key;
        this.label = label;
        this.documentValue = documentValue;
        this.averageValue = averageValue;
    }

    /** The stable key a metric is serialised under. */
    String key() {
        return key;
    }

    /** The name a metric is printed under. */
    String label() {
        return label;
    }

    double valueOf(DocumentMetrics metrics) {
        return documentValue.applyAsDouble(metrics);
    }

    double averageOf(MetricsReport report) {
        return averageValue.applyAsDouble(report);
    }
}
