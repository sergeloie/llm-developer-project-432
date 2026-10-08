package com.carddraft.metrics;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * The three numbers, and what they mean.
 *
 * <p>A record rather than a map because these three have different shapes and different weaknesses,
 * and a caller that reads them by key will eventually read one that is absent. The order is also
 * the order of trust: characteristic match is measured, citation precision is measured, and support
 * is a model's opinion — which {@link MetricsReport} has to label rather than average in silently.
 *
 * @param characteristicMatch share of the reference's characteristics the card got right, compared
 *                            after normalisation. Measured against the reference, so a card cannot
 *                            win by omitting something.
 * @param citationPrecision    share of cited characteristics whose source fragment really does
 *                            contain the cited value. The complement — did the card cite something
 *                            that does not say what it is cited for.
 * @param sourceSupport        a model's judgement of whether each claim is supported by the source
 *                            it names. An estimate and never presented as a measurement.
 * @param characteristicTotal  how many characteristics were judged, so a share is readable next to
 *                            its denominator. A precision of 1.0 over three claims is not the same
 *                            as 1.0 over thirty, and a bare ratio hides that.
 * @param supportUnavailableReason why the support judge could not be asked, or null when it was.
 *                            Kept apart from {@code sourceSupport} because the two are the same
 *                            number and different facts: a card whose claims were all found
 *                            unsupported scores zero, and a card nobody judged scores zero, and a
 *                            report that prints both as 0.000 is claiming a measurement it never
 *                            made.
 * @param generationCost       what the generation calls for this document cost, read from the call
 *                            records by job. Zero for a local model at zero prices — and that zero
 *                            is measured, not assumed: the rows exist whether the prices do or not.
 */
public record DocumentMetrics(String document,
                               double characteristicMatch,
                               double citationPrecision,
                               double sourceSupport,
                               int characteristicTotal,
                               List<String> missedCharacteristics,
                               List<String> unsupportedClaims,
                               String supportUnavailableReason,
                               BigDecimal generationCost) {

    public DocumentMetrics {
        missedCharacteristics = missedCharacteristics == null ? List.of() : List.copyOf(missedCharacteristics);
        unsupportedClaims = unsupportedClaims == null ? List.of() : List.copyOf(unsupportedClaims);
    }

    public DocumentMetrics(String document, double characteristicMatch, double citationPrecision,
                            double sourceSupport, int characteristicTotal,
                            List<String> missedCharacteristics, List<String> unsupportedClaims) {
        this(document, characteristicMatch, citationPrecision, sourceSupport, characteristicTotal,
                missedCharacteristics, unsupportedClaims, null, BigDecimal.ZERO);
    }

    public DocumentMetrics(String document, double characteristicMatch, double citationPrecision,
                            double sourceSupport, int characteristicTotal,
                            List<String> missedCharacteristics, List<String> unsupportedClaims,
                            String supportUnavailableReason) {
        this(document, characteristicMatch, citationPrecision, sourceSupport, characteristicTotal,
                missedCharacteristics, unsupportedClaims, supportUnavailableReason, BigDecimal.ZERO);
    }

    /** Whether the support number is a measurement rather than the absence of one. */
    public boolean supportMeasured() {
        return supportUnavailableReason == null;
    }

    /**
     * No card was produced, so nothing was measured. Not the same as a card scoring zero.
     *
     * <p>The reason is recorded as the unavailable support reason rather than as a missed
     * characteristic, so the report cannot list the same document twice under two different
     * headings: a document that never generated has no unmatched characteristics, because there was
     * no card for a characteristic to be missing from.
     */
    public static DocumentMetrics failed(String document, String reason) {
        return failed(document, reason, BigDecimal.ZERO);
    }

    /**
     * A failure that still billed model calls.
     *
     * <p>A document that never generated can still have cost rows: the attempts happened under
     * this document's job before the failure. Zero here would claim nothing was spent, and the
     * report would disagree with the call records it is meant to summarise.
     */
    public static DocumentMetrics failed(String document, String reason, BigDecimal cost) {
        return new DocumentMetrics(document, 0, 0, 0, 0, List.of(), List.of(), reason, cost);
    }

    /**
     * The weakest of the three, as a name and a value.
     *
     * <p>Support is compared on the same scale as the other two even though it is an estimate,
     * because the question "which number should we look at first" has to be answerable. The report
     * labels it as an estimate rather than pretending the comparison is exact.
     */
    public String weakestMetric() {
        double worst = Math.min(characteristicMatch, Math.min(citationPrecision, sourceSupport));
        if (worst == characteristicMatch) {
            return "characteristic match";
        }
        return worst == citationPrecision ? "citation precision" : "source support";
    }

    public double weakestValue() {
        return Math.min(characteristicMatch, Math.min(citationPrecision, sourceSupport));
    }

    public Map<String, Double> asMap() {
        return Map.of(
                "characteristicMatch", characteristicMatch,
                "citationPrecision", citationPrecision,
                "sourceSupport", sourceSupport);
    }
}