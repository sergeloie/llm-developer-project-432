package com.carddraft.services;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("card.generation")
public record GenerationSettings(

        /**
         * Rounds of draft-and-review.
         *
         * <p>Finite and explicit because the loop's failure mode is a property of the model, not
         * a bug: a generator that fixes one objection while introducing another will otherwise
         * spin forever, at the model's price.
         */
        @DefaultValue("3") @Min(1) int maxRewriteRounds,

        /**
         * Below this confidence a card waits for a person instead of being reported as done.
         *
         * <p>The important threshold in the service. A confident fabrication is worse than an
         * occasional refusal, because a refusal is visible and a fabrication is not — so the
         * default sits high enough that a card has to be clearly supported to pass on its own.
         */
        @DefaultValue("0.7") @NotNull Double confidenceThreshold) {
}
