package com.carddraft.llm;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

/**
 * One model call, as recorded.
 *
 * <p>A record written by the client itself, for one reason: a call site cannot forget to record a
 * call it never made visible. A cost figure assembled from the places that remember to count is a
 * figure that is right until the first path someone forgot, and nothing marks the difference — the
 * total is simply short.
 *
 * @param tier    which configured slot made the call, {@code main} or {@code utility}. Held as a
 *                name rather than a model string so a model rename does not fragment the history
 *                and the breakdown query can group on something stable.
 * @param cost    exact decimal, never a double. Prices per million tokens and token counts are both
 *                decimal and both exact; a float is wrong in the last places, which is invisible
 *                per call and visible in a total.
 */
public record ModelCallRecord(String jobId, String tier, String model, String operation,
                              int inputTokens, int outputTokens, BigDecimal cost,
                              Duration duration, Instant calledAt) {

    public ModelCallRecord {
        if (inputTokens < 0 || outputTokens < 0) {
            throw new IllegalArgumentException("token counts cannot be negative; got "
                    + inputTokens + " in, " + outputTokens + " out");
        }
        if (cost == null || cost.signum() < 0) {
            throw new IllegalArgumentException("a cost is never negative and never absent; got " + cost);
        }
        if (duration == null || duration.isNegative()) {
            throw new IllegalArgumentException("a call took time; a negative duration is a clock problem");
        }
        tier = tier == null || tier.isBlank() ? ModelTier.MAIN.wireName() : tier;
    }

    /** Total tokens billed, which is what a per-token price is applied to when there is only one. */
    public int totalTokens() {
        return inputTokens + outputTokens;
    }
}