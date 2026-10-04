package com.carddraft.agents;

import java.util.List;

/**
 * What the review role found. The issues become the next round's feedback.
 *
 * <p>A rejected draft is still kept: it is imperfect but it exists, and the issues show what the
 * reviewer objected to.
 */
public record CritiqueReport(Verdict verdict, List<String> issues) {

    public CritiqueReport {
        issues = issues == null ? List.of() : List.copyOf(issues);
    }
}
