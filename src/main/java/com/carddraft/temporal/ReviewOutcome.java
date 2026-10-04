package com.carddraft.temporal;

import java.util.List;

/**
 * What the review activity reports back to the workflow.
 *
 * <p>A typed value rather than raw JSON so the workflow does not have to parse anything. Workflow
 * code is replayed from history, and every line of it must be a pure function of what was
 * recorded; parsing is pure but it is also code that can change under a history, which is exactly
 * the fragility the type removes.
 *
 * @param approved whether the draft passed review
 * @param issues   what the reviewer objected to, carried into the next round
 */
public record ReviewOutcome(boolean approved, List<String> issues) {

    public ReviewOutcome {
        issues = issues == null ? List.of() : List.copyOf(issues);
    }
}
