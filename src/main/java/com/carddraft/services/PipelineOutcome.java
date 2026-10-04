package com.carddraft.services;

import com.carddraft.agents.CardDraft;

/**
 * The pipeline's result: the draft, how many rounds it took, and what came of it.
 *
 * <p>The draft is present even on rejection. It is not perfect, but it exists, and the issues
 * the reviewer raised show what it objected to.
 */
public record PipelineOutcome(CardDraft draft, int attempts, PipelineVerdict verdict) {
}
