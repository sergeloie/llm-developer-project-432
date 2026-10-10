package com.carddraft.services;

import com.carddraft.agents.ProductCard;

/**
 * The pipeline's result: the draft, how many rounds it took, what came of it, and whether a person
 * has to look at it.
 *
 * <p>The draft is present even on rejection. It is not perfect, but it exists, and the issues
 * the reviewer raised show what it objected to.
 *
 * @param awaitingHuman true when the card is below the confidence threshold. A rejection is
 *                      always awaiting a human too — there is nothing else it could be.
 */
public record PipelineOutcome(ProductCard draft, int attempts, PipelineVerdict verdict, boolean awaitingHuman) {

    public boolean approved() {
        return verdict == PipelineVerdict.APPROVED && !awaitingHuman;
    }
}
