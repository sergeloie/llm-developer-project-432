package com.carddraft.embeddings;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Embedding model settings.
 *
 * <p>The two prefix templates are the reason this is configuration rather than code. They come from
 * the model's card, and the families that require them encode a query and a document differently
 * on purpose. Applying the wrong one — or none — does not error: the vectors stay valid and the
 * ranking quietly stops meaning anything, which is the most expensive kind of retrieval bug
 * because nothing reports it.
 *
 * <p>Measured on the development machine, and the reason the calibration is a test: adding the
 * prescribed prefixes moved correct-pair similarity from 0.529 down to 0.500 while raising the
 * margin over unrelated text from 0.374 to 0.447. The prefixes help retrieval and simultaneously
 * invalidate a threshold calibrated without them, so the threshold is only ever derived against
 * the configuration that will actually run.
 */
@Validated
@ConfigurationProperties("card.embedding")
public record EmbeddingSettings(
        @DefaultValue("http://127.0.0.1:1234") @NotBlank String baseUrl,

        @DefaultValue("text-embedding-embeddinggemma-300m") @NotBlank
        String model,

        /**
         * Must match the vector column's width. Verified on every response rather than trusted,
         * because a mismatch is otherwise discovered by the first search that fails.
         */
        @DefaultValue("768") @Min(1) int dimension,

        @DefaultValue("task: search result | query: ") @NotBlank
        String queryPrefix,

        @DefaultValue("title: ") @NotBlank String documentPrefix,

        @DefaultValue("120s") @NotNull Duration timeout) {}
