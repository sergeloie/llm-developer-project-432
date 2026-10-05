package com.carddraft.metrics;

import java.util.List;
import java.util.Map;

/**
 * A model's verdict on whether a card's claims are supported by the sources it names.
 *
 * <p>Recorded as data rather than a text verdict so the harness can count it. The judge is asked
 * for one boolean per characteristic, and a missing key is itself information: the number of
 * characteristics the judge did not answer is reported alongside its score rather than being
 * folded into it, because a judge that answered two of twelve has not scored a card.
 *
 * @param supported  characteristic name to whether its source really does state that value
 * @param reasoning  the judge's own words per characteristic, kept so a low score can be read
 *                   rather than merely believed
 */
public record SupportJudgement(Map<String, Boolean> supported, Map<String, String> reasoning) {

    public SupportJudgement {
        supported = supported == null ? Map.of() : Map.copyOf(supported);
        reasoning = reasoning == null ? Map.of() : Map.copyOf(reasoning);
    }

    /**
     * The share of a known set of claims the judge marked supported.
     *
     * <p>The denominator is {@code expectedClaims} rather than what the judge chose to answer, and
     * that is the whole correction. Dividing by the judge's own output lets a judge that answered one
     * easy claim out of twelve score perfectly — a harness that a lazy judge improves rather than
     * degrades is worse than none, because its number rises when quality falls.
     *
     * <p>Zero over nothing rather than one: a judge that answered nothing has not verified a card.
     */
    public double score(int expectedClaims) {
        if (expectedClaims <= 0) {
            return 0;
        }
        long yes = supported.values().stream().filter(Boolean::booleanValue).count();
        return (double) yes / expectedClaims;
    }

    /** Convenience for when the judge answered every claim it was asked about. */
    public double score() {
        return score(supported.size());
    }

    /**
 * Claims the judge did not mark supported, among those it was asked about.
 *
 * <p>Takes the expected names because an omission and an explicit false score the same way and must
 * be listed the same way. Listing only the keys the judge returned would make its silence invisible
 * in the report — exactly the failure the score already accounts for.
 */
public List<String> unsupportedAmong(List<String> expected) {
        return expected.stream()
                .filter(name -> !Boolean.TRUE.equals(supported.get(name)))
                .toList();
    }

    /** Claims the judge itself marked false. An omission is not visible here; see above. */
    public List<String> unsupported() {
        return supported.entrySet().stream()
                .filter(entry -> !entry.getValue())
                .map(Map.Entry::getKey)
                .toList();
    }
}