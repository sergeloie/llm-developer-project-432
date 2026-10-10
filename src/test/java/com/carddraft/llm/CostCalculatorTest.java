package com.carddraft.llm;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The cost arithmetic, exercised at prices a local model would never have.
 *
 * <p>The point of the non-zero rates is that a zero price exercises none of the arithmetic. Every
 * division, every scaling, every rounding path is dead code at zero, so a bug in it would sit
 * unnoticed until the day someone pointed this configuration at a paid model — which is exactly the
 * day the number is relied upon. Paying for nothing here is how the paid path gets proven before it
 * is used.
 */
class CostCalculatorTest {

    /** Rates quoted the way providers actually quote them: per million, to six places. */
    private static final CostCalculator PRICED = new CostCalculator(new BigDecimal("3.00"), new BigDecimal("15.00"));

    private static final CostCalculator FREE = new CostCalculator(BigDecimal.ZERO, BigDecimal.ZERO);

    @Test
    void aCallCostsItsInputAndOutputAtTheirOwnRates() {
        // 1000 in at 3.00/M = 0.003; 500 out at 15.00/M = 0.0075. Total 0.0105.
        assertThat(PRICED.costOf(1000, 500)).isEqualByComparingTo("0.0105");
    }

    /**
     * The two rates differ by five times, so using one for both is not a rounding error.
     *
     * <p>A card's prompt carries the schema and every retrieved fragment, and its answer is shorter
     * than its prompt. Charging the whole thing at one rate therefore misprices by the difference
     * between the rates multiplied by the prompt — which is most of the call.
     */
    @Test
    void inputAndOutputAreNotChargedAtTheSameRate() {
        BigDecimal wrongAsOneRate = new BigDecimal("1500")
                .multiply(new BigDecimal("3.00"))
                .divide(new BigDecimal("1000000"), 8, RoundingMode.HALF_UP);

        assertThat(PRICED.costOf(1000, 500))
                .as("splitting the two rates is worth a fifth of the call here")
                .isNotEqualByComparingTo(wrongAsOneRate);
    }

    @Test
    void aFreeModelCostsNothingRatherThanFailing() {
        assertThat(FREE.costOf(1_000_000, 1_000_000)).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(FREE.costOf(0, 0)).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void aCallWithNoTokensCostsNothing() {
        assertThat(PRICED.costOf(0, 500)).isEqualByComparingTo("0.0075");
        assertThat(PRICED.costOf(1000, 0)).isEqualByComparingTo("0.0030");
    }

    /**
     * A fraction far below the column's scale must not be rounded away to nothing.
     *
     * <p>At one dollar per million, a single output token is a millionth of a cent. Rounding that
     * to eight places leaves 0.000001, but a scheme that rounded to six would report zero — and a
     * breakdown in which some calls cost nothing and others cost something is harder to read than
     * one where everything is small.
     */
    @Test
    void aTinyCostSurvivesRatherThanDisappearing() {
        CostCalculator cheap = new CostCalculator(new BigDecimal("0.000001"), new BigDecimal("0.000001"));

        BigDecimal oneToken = cheap.costOf(0, 1);

        assertThat(oneToken).isNotEqualByComparingTo(BigDecimal.ZERO);
        assertThat(oneToken.scale()).isLessThanOrEqualTo(CostCalculator.SCALE);
    }

    /**
     * Exact decimal arithmetic, not a float that happens to look right.
     *
     * <p>The sum over a card's calls is the number that gets reported, so the per-call figure has to
     * be exact rather than merely close. 0.1 + 0.2 is the canonical float failure and this cost is
     * built to hit the same shape: three rates that cannot be represented in binary.
     */
    @Test
    void awkwardRatesAreExactRatherThanNearlyRight() {
        CostCalculator awkward = new CostCalculator(new BigDecimal("0.1"), new BigDecimal("0.2"));

        BigDecimal cost = awkward.costOf(3, 3);

        assertThat(cost.setScale(CostCalculator.SCALE, RoundingMode.UNNECESSARY))
                .as("0.1 * 3 + 0.2 * 3 per million, exactly")
                .isEqualByComparingTo("0.0000009");
    }

    @Test
    void theResultAlwaysFitsTheColumnsScale() {
        CostCalculator awkward = new CostCalculator(new BigDecimal("1.23456789"), new BigDecimal("9.87654321"));

        assertThat(awkward.costOf(123_456, 654_321).scale()).isEqualTo(CostCalculator.SCALE);
    }

    @Test
    void negativeTokenCountsAreRefused() {
        assertThatThrownBy(() -> PRICED.costOf(-1, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("-1");
    }

    /**
     * An absent price is not the same as a free one.
     *
     * <p>Refused, because the two mean opposite things and only one of them is safe: zero says this
     * model is free, absent says nobody decided. Treating absent as zero would make a
     * misconfiguration look like a deliberate local deployment, and the totals would look correct.
     */
    @Test
    void anAbsentPriceIsRefusedRatherThanTreatedAsFree() {
        assertThatThrownBy(() -> new CostCalculator(null, BigDecimal.ONE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be set");
        assertThatThrownBy(() -> new CostCalculator(BigDecimal.ONE, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be set");
    }

    @Test
    void aNegativePriceIsRefused() {
        assertThatThrownBy(() -> new CostCalculator(new BigDecimal("-1"), BigDecimal.ONE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negative");
    }

    @Test
    void summingPerCallCostsMatchesTheCostOfTheTotals() {
        BigDecimal sum = PRICED.costOf(1200, 300).add(PRICED.costOf(900, 250));

        assertThat(sum)
                .as("the reported total is the sum of the rows, not a separately computed figure")
                .isEqualByComparingTo(PRICED.costOf(2100, 550));
    }

    @Test
    void thePricesInUseAreReadable() {
        assertThat(PRICED.inputPricePerMillion()).isEqualByComparingTo("3.00");
        assertThat(PRICED.outputPricePerMillion()).isEqualByComparingTo("15.00");
    }
}
