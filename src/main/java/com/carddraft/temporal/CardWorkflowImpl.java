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
     * How many times a card may come back for a fabricated citation before a person sees it.
     *
     * <p>One. A second failure is not a model that needed another try: a model that was shown five
     * fragments, cited all five correctly and still invented a sixth has a systematic problem, and
     * another round produces another fabrication with the same shape. Sending that to a person is
     * the honest outcome, and it is also the cheap one — the alternative is spending a generation
     * per attempt to arrive at a card nobody trusts.
     */
    private static final int MAX_CITATION_REWORKS = 1;

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
        CitationCheck citations = new CitationCheck(true, List.of(), 0, 0);

        try {
            if (request.retrievesFromDocuments()) {
                publish(jobId, JobState.RETRIEVING, null);
                String contextText = steps.retrieveAndAssemble(
                        jobId, request.productHint(), request.documentIds());

                List<String> issues = List.of();
                boolean escalated = false;
                boolean clean = false;

                // Held here rather than on the activity's result, because each check returns a
                // fresh count and overwriting would reset it to zero on every round - leaving the
                // allowance permanently unspent and the card in rework forever. Workflow-local
                // state is also the only version that survives a replay correctly, since it is
                // derived from the recorded decisions.
                int citationFailures = 0;

                // Bounded by both the caller's round budget and the citation allowance, because they
                // are different limits answering different questions. maxRounds is what the caller
                // was willing to pay for; the citation count is how many times a model may be
                // asked to fix the same class of mistake before a person looks at it. Neither
                // substitutes for the other: a reviewer that objects repeatedly is bounded by
                // maxRounds alone, and letting it consume the citation allowance would escalate
                // to a human for a reason that has nothing to do with citations.
                while (attempts < request.maxRounds() && citationFailures <= MAX_CITATION_REWORKS) {
                    attempts++;
                    publish(jobId, JobState.GENERATING, "attempt " + attempts);
                    steps.countAttempt(jobId);
                    draftJson = steps.generateFromContext(jobId, contextText, issues);

                    publish(jobId, JobState.REVIEWING, "attempt " + attempts);
                    CritiqueStep review = reviewAgainstContext(jobId, contextText, draftJson);

                    if (review.approved()) {
                        reviewerApproved = true;
                        citations = steps.checkCitations(jobId, draftJson);
                        if (citations.clean()) {
                            clean = true;
                            break;
                        }
                        issues = citations.messages();
                        citationFailures++;
                        if (citationFailures > MAX_CITATION_REWORKS) {
                            escalated = true;
                        }
                    } else {
                        issues = review.issues();
                    }
                }

                status = escalated
                        ? "awaiting human decision after citation failures"
                        : clean
                                ? "awaiting human decision"
                                : "awaiting human decision after review";
                publish(jobId, JobState.AWAITING_HUMAN,
                        escalated ? citations.messages().toString() : null);
                Workflow.await(() -> decision != null);

                status = escalated ? "awaiting human decision after citation failures"
                        : "awaiting human decision";
                publish(jobId, JobState.AWAITING_HUMAN, escalated ? citations.messages().toString() : null);
                Workflow.await(() -> decision != null);
            } else {
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
            }
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

    /**
     * The model's judgement of the card, and the mechanical check, as one thing.
     *
     * <p>Two sources rather than one because they fail differently. The reviewer catches a right
     * fact attributed to the wrong fragment, which no label check can express because every label
     * is real. The check catches a plausible label pointing at a fragment the model never saw, which
     * a reviewer cannot catch because the fragment looks perfectly reasonable.
     */
    private CritiqueStep reviewAgainstContext(String jobId, String contextText, String draftJson) {
        ReviewOutcome verdict = steps.reviewCardAgainstContext(jobId, contextText, draftJson);
        return new CritiqueStep(verdict.approved(), verdict.issues());
    }

    private record CritiqueStep(boolean approved, List<String> issues) {
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
