package com.carddraft.documents;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Min;

/**
 * Chunking sizes, in characters rather than tokens.
 *
 * <p>Characters, deliberately: the tokenizer lives in the model server, not in this process, and
 * importing one purely to count would be a dependency bought for nothing. At roughly four
 * characters per token on Russian text, the default of 1200 is about 300 tokens — enough for a
 * characteristic and its context, small enough that several fit a prompt.
 */
@Validated
@ConfigurationProperties("card.chunking")
public record ChunkingSettings(
        @DefaultValue("1200") @Min(100) int chunkSize,

        /**
         * Overlap, so a statement spanning a boundary is still found whole in one chunk.
         */
        @DefaultValue("200") @Min(0) int chunkOverlap,

        @DefaultValue("26214400") @Min(1024) long maxUploadBytes) {

    public ChunkingSettings {
        if (chunkOverlap >= chunkSize) {
            throw new IllegalArgumentException("chunk overlap must be smaller than the chunk size");
        }
    }
}
