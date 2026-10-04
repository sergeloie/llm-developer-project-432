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
        @DefaultValue("8s") @NotNull Duration retryMaxDelay) {

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
