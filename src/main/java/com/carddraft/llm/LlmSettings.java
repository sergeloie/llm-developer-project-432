package com.carddraft.llm;

import java.time.Duration;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Model settings, bound and validated at startup.
 */
@Validated
@ConfigurationProperties("card.llm")
public record LlmSettings(

        /** Generates cards. */
        @DefaultValue("qwen/qwen3.5-9b") @NotBlank String mainModel,

        /** Reviews, classifies, screens for injection and judges. */
        @DefaultValue("qwen/qwen3-4b-2507") @NotBlank String utilityModel,

        @DefaultValue("120s") @NotNull Duration timeout,

        /**
         * Attempts for one call, including the first. Three is deliberate: past that, a provider
         * that is genuinely down is being billed for the privilege of finding out again.
         */
        @DefaultValue("3") @Min(1) int maxAttempts,

        @DefaultValue("500ms") @NotNull Duration retryBaseDelay,
        @DefaultValue("8s") @NotNull Duration retryMaxDelay,

        /**
         * Repair rounds for a response that arrived unusable.
         *
         * <p>Bounded, because repair is a generation too: a model that cannot satisfy the
         * contract will not satisfy it on the fourth attempt either, and an unbounded loop here
         * costs more than the generation it was meant to rescue.
         */
        @DefaultValue("2") @Min(0) int maxRepairAttempts,

        /**
         * Prices per million tokens, per tier, as exact decimals.
         *
         * <p>Configuration rather than a constant because the local models are free and the
         * arithmetic still has to be right: a rate of zero exercises none of it, so a bug in the
         * cost calculation would sit hidden until the day someone pointed this at a paid model.
         * The unit tests therefore use non-zero prices, and the paid path is proven before it is
         * used rather than on its first day.
         *
         * <p>As {@code BigDecimal} because the values arrive from configuration as text and a
         * double would lose the trailing precision a quoted price carries.
         */
        @DefaultValue("0") @NotNull java.math.BigDecimal mainInputPricePerMillion,
        @DefaultValue("0") @NotNull java.math.BigDecimal mainOutputPricePerMillion,
        @DefaultValue("0") @NotNull java.math.BigDecimal utilityInputPricePerMillion,
        @DefaultValue("0") @NotNull java.math.BigDecimal utilityOutputPricePerMillion) {

    /**
     * The calculator for one tier.
     *
     * <p>Built on demand rather than held, because the settings are immutable and the two
     * calculators are two pairs of numbers; caching them would be state that could disagree with the
     * configuration it came from.
     */
    public CostCalculator calculatorFor(ModelTier tier) {
        return switch (tier) {
            case MAIN -> new CostCalculator(mainInputPricePerMillion, mainOutputPricePerMillion);
            case UTILITY -> new CostCalculator(utilityInputPricePerMillion, utilityOutputPricePerMillion);
        };
    }

    /** The model name for a tier, which is where the tier selection actually happens. */
    public String modelFor(ModelTier tier) {
        return switch (tier) {
            case MAIN -> mainModel;
            case UTILITY -> utilityModel;
        };
    }

    /**
     * Delay before attempt {@code nextAttempt}, growing exponentially to a ceiling and carrying
     * a random component.
     *
     * <p>The growth keeps a struggling provider from being hit again immediately, because if it
     * could not cope now it will not cope in half a second. The ceiling stops the delay becoming
     * absurd. The random part keeps workers released by the same rate limit from waking together
     * and recreating the overload they just caused.
     */
    public Duration delayBefore(int nextAttempt) {
        long exponential = Math.multiplyExact(retryBaseDelay.toMillis(), 1L << Math.min(nextAttempt - 1, 20));
        long capped = Math.min(exponential, retryMaxDelay.toMillis());
        long jittered = capped + (long) (capped * 0.25 * java.util.concurrent.ThreadLocalRandom.current().nextDouble());
        return Duration.ofMillis(jittered);
    }
}
