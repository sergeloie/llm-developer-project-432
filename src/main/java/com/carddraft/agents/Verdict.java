package com.carddraft.agents;

/**
 * The review role's decision about a draft.
 *
 * <p>A controlling signal rather than a description: the pipeline branches on it, so a model
 * answering something outside these two cases cannot quietly take the wrong branch.
 */
public enum Verdict {
    APPROVE,
    REGENERATE
}
