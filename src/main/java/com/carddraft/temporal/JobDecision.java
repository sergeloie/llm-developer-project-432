package com.carddraft.temporal;

import java.util.Locale;
import java.util.Optional;

/**
 * The person's answer, shared by the HTTP request and the workflow signal.
 *
 * <p>One type rather than the same two string literals spelled out in the controller and in the
 * workflow: a vocabulary that exists in two places drifts, and the drift is silent — this side
 * sends {@code approve}, that side waits for {@code approved}, and the job simply never moves.
 *
 * <p>The wire name keeps the workflow's signal payload and the HTTP {@code decision} field
 * identical, so a caller's word and the process's word are the same word.
 */
public enum JobDecision {

    APPROVE("approve"),
    REJECT("reject");

    private final String wireName;

    JobDecision(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    /**
     * The decision a caller named, or empty when it is not one of the two.
     *
     * <p>Case and surrounding space are ignored on purpose: this is a person's answer rather than a
     * machine's enum.
     */
    public static Optional<JobDecision> fromWireName(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String spoken = value.strip().toLowerCase(Locale.ROOT);
        for (JobDecision decision : values()) {
            if (decision.wireName.equals(spoken)) {
                return Optional.of(decision);
            }
        }
        return Optional.empty();
    }
}
