package com.carddraft.search;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Min;

/**
 * How indexing is driven, as opposed to how a single text is encoded.
 *
 * <p>Separate from {@link com.carddraft.embeddings.EmbeddingSettings} because the two answer
 * different questions: that one describes the model, this one describes the loop over it.
 *
 * <p>{@code maxBatches} is a safety bound rather than a convenience. A chunk whose document never
 * reaches 'indexed' stays unembedded forever, so an unbounded loop sits there rather than reporting
 * the stall — the run appears to be working and nothing ever advances.
 */
@Validated
@ConfigurationProperties("card.embedding.indexing")
public record IndexingSettings(
        @DefaultValue("16") @Min(1) int batchSize,

        @DefaultValue("1000") @Min(1) int maxBatches) {}
