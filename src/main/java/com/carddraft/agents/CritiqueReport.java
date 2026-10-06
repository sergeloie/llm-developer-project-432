package com.carddraft.agents;

import java.util.List;

/**
 * What the review role found. The issues become the next round's feedback.
 *
 * <p>A rejected draft is still kept: it is imperfect but it exists, and the issues show what the
 * reviewer objected to.
 */
public record CritiqueReport(Verdict verdict, List<ReviewIssue> issues) {

    public CritiqueReport {
        if (verdict == null) {
            throw new IllegalArgumentException("verdict is required; a review without a decision cannot drive the rework loop");
        }
        issues = issues == null ? List.of() : List.copyOf(issues);
    }
}
