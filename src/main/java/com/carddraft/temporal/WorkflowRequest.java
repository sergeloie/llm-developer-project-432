package com.carddraft.temporal;

/**
 * The workflow's input.
 *
 * <p>{@code maxRounds} travels as an argument rather than being read from configuration inside
 * the workflow. Configuration can change between replays of the same history, and a loop whose
 * bound shifts mid-replay takes a different path than the one recorded — which is the failure
 * mode replay exists to prevent. Passing it in also makes the bound part of the recorded input,
 * so the history says what the budget was.
 */
public record WorkflowRequest(String jobId, String supplierText, int maxRounds) {
}
