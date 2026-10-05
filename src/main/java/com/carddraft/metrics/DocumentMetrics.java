package com.carddraft.metrics;

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
 */
public record DocumentMetrics(String document,
                              double characteristicMatch,
                              double citationPrecision,
                              double sourceSupport,
                              int characteristicTotal,
                              List<String> missedCharacteristics,
                              List<String> unsupportedClaims) {

    public DocumentMetrics {
        missedCharacteristics = missedCharacteristics == null ? List.of() : List.copyOf(missedCharacteristics);
        unsupportedClaims = unsupportedClaims == null ? List.of() : List.copyOf(unsupportedClaims);
    }

    /** No card was produced, so nothing was measured. Not the same as a card scoring zero. */
    public static DocumentMetrics failed(String document, String reason) {
        return new DocumentMetrics(document, 0, 0, 0, 0, List.of(reason), List.of());
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