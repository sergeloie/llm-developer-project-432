package com.carddraft.temporal;

import java.time.Duration;
import java.util.List;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.workflow.Workflow;

/**
 * Extract, draft-and-review within a budget, then wait for a person.
 *
 * <p>This is the same algorithm as the synchronous pipeline, written a second time because it is
 * a different transport. What is shared is the substance — the roles, the prompts and the model
 * client all live in services and are called unchanged. What differs is only that the calls are
 * now steps, and that progress has to be published as it happens.
 *
 * <p>Determinism rules, held by convention because the Java SDK does not sandbox workflow code
 * (ADR-0004): no clock, no randomness, no configuration reads, no network. Timeouts are constants
 * for the same reason configuration is not read — a value that can change between replays sends
 * the same history down a different path.
 */
public class CardWorkflowImpl implements CardWorkflow {

    private static final String DECISION_APPROVE = "approve";
    private static final String DECISION_REJECT = "reject";

    /**
     * Module constants, not configuration. A generation step waits on the model for tens of
     * seconds and can legitimately take minutes; five minutes is a ceiling, not a target.
     */
    private static final Duration STEP_TIMEOUT = Duration.ofMinutes(5);

    /**
     * A status write is a local database update: it either lands or the database is down. Two
     * attempts, because the default is unlimited — and an unlimited default means a step that
     * fails deterministically is retried forever, with the timeout on the whole step as the only
     * real limit.
     */
    private static final Duration STATUS_TIMEOUT = Duration.ofSeconds(20);

    private static final RetryOptions STEP_RETRY = RetryOptions.newBuilder()
            .setMaximumAttempts(3)
            .setInitialInterval(Duration.ofSeconds(1))
            .build();

    private static final RetryOptions STATUS_RETRY = RetryOptions.newBuilder()
            .setMaximumAttempts(2)
            .setInitialInterval(Duration.ofSeconds(1))
            .build();

    private final CardActivities steps = Workflow.newActivityStub(
            CardActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(STEP_TIMEOUT)
                    .setRetryOptions(STEP_RETRY)
                    .build());

    private final CardActivities statusWrites = Workflow.newActivityStub(
            CardActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(STATUS_TIMEOUT)
                    .setRetryOptions(STATUS_RETRY)
                    .build());

    private String status = "starting";
    private String decision;

    @Override
    public WorkflowResult run(WorkflowRequest request) {
        String jobId = request.jobId();
        String draftJson = "{}";
        int attempts = 0;
        boolean reviewerApproved = false;

        try {
            publish(jobId, JobState.EXTRACTING, null);
            String factsJson = steps.extractFacts(jobId, request.supplierText());

            List<String> issues = List.of();
            while (attempts < request.maxRounds()) {
                attempts++;
                publish(jobId, JobState.GENERATING, "attempt " + attempts);
                steps.countAttempt(jobId);
                draftJson = steps.generateDraft(jobId, factsJson, issues);

                publish(jobId, JobState.REVIEWING, "attempt " + attempts);
                ReviewOutcome review = steps.reviewDraft(jobId, factsJson, draftJson);
                if (review.approved()) {
                    reviewerApproved = true;
                    break;
                }
                issues = review.issues();
            }

            status = "awaiting human decision";
            publish(jobId, JobState.AWAITING_HUMAN, null);
            Workflow.await(() -> decision != null);
        } catch (RuntimeException e) {
            publish(jobId, JobState.FAILED, e.getMessage());
            throw ApplicationFailure.newNonRetryableFailureWithCause(
                    "job " + jobId + " failed", "CardJobFailed", e);
        }

        JobState finalState = DECISION_APPROVE.equals(decision) ? JobState.APPROVED : JobState.REJECTED;
        status = finalState.wireName();
        publish(jobId, finalState, null);

        return new WorkflowResult(jobId, draftJson, attempts, reviewerApproved, decision);
    }

    @Override
    public void approve() {
        this.decision = DECISION_APPROVE;
    }

    @Override
    public void reject() {
        this.decision = DECISION_REJECT;
    }

    @Override
    public String currentStatus() {
        return status;
    }

    private void publish(String jobId, JobState state, String detail) {
        statusWrites.writeStatus(jobId, state.wireName(), detail);
    }
}
