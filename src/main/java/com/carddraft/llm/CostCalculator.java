package com.carddraft.llm;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Turns token counts and prices into money, exactly.
 *
 * <p>{@link BigDecimal} throughout, and the reason is not fastidiousness. Prices are quoted per
 * million tokens to a precision a binary float cannot represent, and a card's cost is a sum over
 * several calls. Each individual float error would be far below anything anyone looks at; the sum
 * of them is what ends up on a report, and a total that is quietly wrong is worse than one that is
 * absent.
 *
 * <p>Rounding happens once, at the end, to the scale of the column. Rounding per call and summing
 * rounded figures is a different number from rounding the sum, and the second is the honest one.
 */
public class CostCalculator {

    /**
     * Matches NUMERIC(18,12), so a computed cost always fits without being rounded away.
     *
     * <p>Wider than it looks necessary because prices are per million tokens: one token costs
     * price/1000000, which at 0.01 per million is exactly the eighth decimal place. Eight places
     * would put the cheapest realistic per-token cost on the boundary and round single-token calls
     * to nothing.
     */
    public static final int SCALE = 12;

    private static final BigDecimal TOKENS_PER_PRICE_UNIT = new BigDecimal("1000000");

    private final BigDecimal inputPricePerMillion;
    private final BigDecimal outputPricePerMillion;

    /**
     * @param inputPricePerMillion  price of a million input tokens; zero for a local model
     * @param outputPricePerMillion price of a million output tokens; zero for a local model
     */
    public CostCalculator(BigDecimal inputPricePerMillion, BigDecimal outputPricePerMillion) {
        this.inputPricePerMillion = nonNegative(inputPricePerMillion, "input");
        this.outputPricePerMillion = nonNegative(outputPricePerMillion, "output");
    }

    private static BigDecimal nonNegative(BigDecimal value, String which) {
        if (value == null) {
            throw new IllegalArgumentException("the " + which + " price must be set, not absent; "
                    + "use zero for a local model rather than leaving it out");
        }
        if (value.signum() < 0) {
            throw new IllegalArgumentException("a price cannot be negative; " + which + " is " + value);
        }
        return value;
    }

    /**
     * The cost of one call.
     *
     * <p>Split into two terms because input and output are priced differently, and the difference is
     * usually large: a card's prompt carries the schema and the fragments, and its answer is shorter
     * than the prompt. Treating the total as one price understates or overstates by the difference
     * between the two rates multiplied by the prompt, which is not a rounding error.
     */
    public BigDecimal costOf(int inputTokens, int outputTokens) {
        if (inputTokens < 0 || outputTokens < 0) {
            throw new IllegalArgumentException("a call cannot have consumed negative tokens; got "
                    + inputTokens + " in, " + outputTokens + " out");
        }
        BigDecimal input = new BigDecimal(inputTokens)
                .multiply(inputPricePerMillion)
                .divide(TOKENS_PER_PRICE_UNIT, SCALE + 6, RoundingMode.HALF_UP);
        BigDecimal output = new BigDecimal(outputTokens)
                .multiply(outputPricePerMillion)
                .divide(TOKENS_PER_PRICE_UNIT, SCALE + 6, RoundingMode.HALF_UP);
        return input.add(output).setScale(SCALE, RoundingMode.HALF_UP);
    }

    public BigDecimal inputPricePerMillion() {
        return inputPricePerMillion;
    }

    public BigDecimal outputPricePerMillion() {
        return outputPricePerMillion;
    }
}