package com.carddraft.temporal;

/**
 * The workflow's output.
 *
 * @param draftJson the draft as JSON, whether or not it was approved — a rejected draft is kept
 * @param attempts   how many draft-and-review rounds it took
 * @param reviewerVerdict whether the review role approved the draft
 * @param humanDecision    what the person decided, or {@code null} if nobody decided
 */
public record WorkflowResult(String jobId, String draftJson, int attempts,
                              boolean reviewerVerdict, String humanDecision) {
}
