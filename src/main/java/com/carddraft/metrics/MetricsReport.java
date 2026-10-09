package com.carddraft.metrics;

import java.util.List;

/**
 * One run of the harness, as written to the report file.
 *
 * <p>A value rather than something assembled while printing, because the report has to survive the
 * run and be compared with a later one. Reading it back off a console loses the labels, and "before
 * and after" is the entire purpose.
 *
 * @param scope which documents were measured. Recorded so a comparison of two reports says whether
 *              it compared like with like — a subset run against a full run is not a regression.
 */
public record MetricsReport(String runId, String scope, List<DocumentMetrics> perDocument,
                            double averageCharacteristicMatch, double averageCitationPrecision,
                            double averageSourceSupport) {

    public MetricsReport {
        perDocument = perDocument == null ? List.of() : List.copyOf(perDocument);
    }

    /**
     * What the run's generations cost, as the sum of the per-document rows.
     *
     * <p>Derived rather than stored, for the same reason every other aggregate in this codebase
     * is derived: the rows are the source of truth and a stored total would be a second answer
     * to the same question.
     */
    public java.math.BigDecimal totalCost() {
        return perDocument.stream().map(DocumentMetrics::generationCost)
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
    }

    /**
     * The metric to look at first, and the document that is worst on it.
     *
     * <p>Computed here rather than left to the reader because a report that lists three numbers
     * without saying which is weakest is a report everybody argues about differently.
     */
    public String weakestSummary() {
        if (perDocument.isEmpty()) {
            return "nothing was measured";
        }
        Metric weakest = Metric.values()[0];
        for (Metric metric : Metric.values()) {
            if (metric.averageOf(this) < weakest.averageOf(this)) {
                weakest = metric;
            }
        }

        Metric target = weakest;
        DocumentMetrics worstDocument = perDocument.stream()
                .min(java.util.Comparator.comparingDouble(target::valueOf))
                .orElseThrow();

        return "weakest metric: " + target.label()
                + " at " + format(target.averageOf(this))
                + " (average), worst document " + worstDocument.document()
                + " at " + format(target.valueOf(worstDocument));
    }

    private static String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.3f", value);
    }
}