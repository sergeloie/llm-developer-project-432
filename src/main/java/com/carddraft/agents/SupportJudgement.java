package com.carddraft.agents;

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
 * <p>Lives in {@code agents} beside {@link ModelVerdict} rather than in the metrics package, because
 * the model client returns it and the client must not depend on the harness that consumes it. That
 * is the same reason the injection verdict was moved.
 *
 * @param supported         characteristic name to whether its source really does state that value
 * @param reasoning         the judge's own words per characteristic, kept so a low score can be read
 *                          rather than merely believed
 * @param unavailableReason why the judge could not be asked at all, or null when it was asked and
 *                          answered. Distinguishes "checked and found nothing" from "never
 *                          checked", which are the same number and different facts.
 */
public record SupportJudgement(
        Map<String, Boolean> supported, Map<String, String> reasoning, String unavailableReason) {

    public SupportJudgement {
        supported = supported == null ? Map.of() : Map.copyOf(supported);
        reasoning = reasoning == null ? Map.of() : Map.copyOf(reasoning);
    }

    public SupportJudgement(Map<String, Boolean> supported, Map<String, String> reasoning) {
        this(supported, reasoning, null);
    }

    /**
     * A judge that could not be asked.
     *
     * <p>Carries the reason rather than being an empty verdict, because an empty verdict is also
     * what a judge that answered nothing produces and the report has to tell those apart. The score
     * is still zero — the same non-flattering reading an empty answer gets — but the reason travels
     * with it so the report can say the number was never measured instead of printing a zero that
     * reads like a verdict.
     */
    public static SupportJudgement unavailable(String reason) {
        return new SupportJudgement(Map.of(), Map.of(), reason);
    }

    /** Whether a judge was actually asked. False means the support score below is not a measurement. */
    public boolean measured() {
        return unavailableReason == null;
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
