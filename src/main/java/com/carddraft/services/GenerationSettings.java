package com.carddraft.services;

import jakarta.validation.constraints.Min;

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
        @DefaultValue("3") @Min(1) int maxRewriteRounds) {
}
