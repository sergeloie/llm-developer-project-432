package com.carddraft.context;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Min;

/**
 * The context budget.
 *
 * <p>Both limits exist, and they are not the same limit. The chunk count keeps the number of
 * distinct facts small enough to reason about; the character count stops one enormous fragment —
 * a scanned specification table flattened into a single chunk — from consuming the entire budget on
 * its own. Either alone leaves a way to blow through.
 *
 * <p>Twelve chunks and 12 000 characters is roughly a page of supplier prose. Chosen against the
 * supplied documents rather than by intuition: the passport's characteristics span two pages, and
 * the kettle specification needs three fragments for one row, so anything under about eight chunks
 * answers neither document completely.
 */
@Validated
@ConfigurationProperties("card.context")
public record ContextSettings(
        @DefaultValue("12") @Min(1) int maxChunks,

        @DefaultValue("12000") @Min(1) int maxCharacters) {}
