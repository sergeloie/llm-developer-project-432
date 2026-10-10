package com.carddraft.temporal;

import java.time.Duration;
import java.util.List;

import com.carddraft.agents.ProductCard;
import com.carddraft.agents.ReviewIssue;
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
     * A status write is a local database update: it either lands or the database is down. Two
     * attempts, because the default is unlimited — and an unlimited default means a step that
     * fails deterministically is retried forever, with the timeout on the whole step as the only
     * real limit.
     */
    private static final Duration STATUS_TIMEOUT = Duration.ofSeconds(20);

    private static final RetryOptions STATUS_RETRY = RetryOptions.newBuilder()
            .setMaximumAttempts(2)
            .setInitialInterval(Duration.ofSeconds(1))
            .build();

    private static final String ATTEMPT_PREFIX = "attempt ";

    private final CardActivities steps = Workflow.newActivityStub(
            CardActivities.class, StepActivityOptions.options());

    private final CardActivities statusWrites = Workflow.newActivityStub(
            CardActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(STATUS_TIMEOUT)
                    .setRetryOptions(STATUS_RETRY)
                    .build());

    private String status = "starting";
    private JobDecision decision;

    @Override
    public WorkflowResult run(WorkflowRequest request) {
        String jobId = request.jobId();
        String draftJson = "{}";
        int attempts = 0;
        boolean reviewerApproved = false;

        try {
            if (request.retrievesFromDocuments()) {
                publish(jobId, JobState.RETRIEVING, null);
                RetrievedContext retrieved = steps.retrieveAndAssemble(
                        jobId, request.productHint(), request.documentIds());

                // A document that is mostly attack is not generated from the survivors. The
                // omissions would be nobody's choice, and a card with unchosen omissions reads
                // as complete — so the job waits for a person with the screening reason.
                if (retrieved.escalated()) {
                    status = "awaiting human decision after trust escalation";
                    publish(jobId, JobState.AWAITING_HUMAN, retrieved.escalationReason());
                    Workflow.await(() -> decision != null);
                } else {
                    RetrievalLoop loop = runRetrievalLoop(jobId, retrieved.contextText(),
                            request.maxRounds());
                    draftJson = loop.draftJson();
                    attempts = loop.attempts();
                    reviewerApproved = loop.reviewerApproved();
                    status = loop.awaitingStatus();
                    publish(jobId, JobState.AWAITING_HUMAN, loop.awaitingDetail());
                    Workflow.await(() -> decision != null);
                }
            } else {
                publish(jobId, JobState.EXTRACTING, null);
                String factsJson = steps.extractFacts(jobId, request.supplierText());

                List<ReviewIssue> issues = List.of();
                while (attempts < request.maxRounds()) {
                    attempts++;
                    publish(jobId, JobState.GENERATING, ATTEMPT_PREFIX + attempts);
                    statusWrites.countAttempt(jobId, attempts);
                    draftJson = steps.generateDraft(jobId, factsJson, issues);

                    publish(jobId, JobState.REVIEWING, ATTEMPT_PREFIX + attempts);
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
            status = JobState.FAILED.wireName();
            statusWrites.recordFailure(jobId, failureMessage(e));
            throw ApplicationFailure.newNonRetryableFailureWithCause(
                    "job " + jobId + " failed", "CardJobFailed", e);
        }

        if (decision == JobDecision.APPROVE && !ProductCard.isDraftJson(draftJson)) {
            String reason = "approval refused: no draft card was generated, so there is nothing to approve";
            status = JobState.FAILED.wireName();
            statusWrites.recordFailure(jobId, reason);
            return new WorkflowResult(jobId, draftJson, attempts, reviewerApproved, decision.wireName());
        }

        JobState finalState = decision == JobDecision.APPROVE ? JobState.APPROVED : JobState.REJECTED;
        status = finalState.wireName();
        statusWrites.recordOutcome(jobId, finalState.wireName(), draftJson);

        return new WorkflowResult(jobId, draftJson, attempts, reviewerApproved, decision.wireName());
    }

    /**
     * The retrieval branch's generate-and-review loop, and how it ended.
     *
     * <p>Bounded by both the caller's round budget and the citation allowance, because they
     * are different limits answering different questions. maxRounds is what the caller
     * was willing to pay for; the citation count is how many times a model may be
     * asked to fix the same class of mistake before a person looks at it. Neither
     * substitutes for the other: a reviewer that objects repeatedly is bounded by
     * maxRounds alone, and letting it consume the citation allowance would escalate
     * to a human for a reason that has nothing to do with citations.
     */
    private record RetrievalLoop(String draftJson, int attempts, boolean reviewerApproved,
                                 String awaitingStatus, String awaitingDetail) {
    }

    private RetrievalLoop runRetrievalLoop(String jobId, String contextText, int maxRounds) {
        List<ReviewIssue> issues = List.of();
        boolean escalated = false;
        boolean clean = false;
        String draftJson = "{}";
        int attempts = 0;
        boolean reviewerApproved = false;
        CitationCheck citations = new CitationCheck(true, List.of(), 0, 0);

        // Held here rather than on the activity's result, because each check returns a
        // fresh count and overwriting would reset it to zero on every round - leaving the
        // allowance permanently unspent and the card in rework forever. Workflow-local
        // state is also the only version that survives a replay correctly, since it is
        // derived from the recorded decisions.
        int citationFailures = 0;

        while (attempts < maxRounds && citationFailures <= MAX_CITATION_REWORKS) {
            attempts++;
            publish(jobId, JobState.GENERATING, ATTEMPT_PREFIX + attempts);
            statusWrites.countAttempt(jobId, attempts);
            draftJson = steps.generateFromContext(jobId, contextText, issues);

            publish(jobId, JobState.REVIEWING, ATTEMPT_PREFIX + attempts);
            CritiqueStep review = reviewAgainstContext(jobId, contextText, draftJson);

            if (review.approved()) {
                reviewerApproved = true;
                citations = steps.checkCitations(jobId, draftJson);
                if (citations.clean()) {
                    clean = true;
                    break;
                }
                // Citation complaints arrive as sentences rather than as field-level issues, because the check
                // reports which labels were fabricated rather than which line of the card
                // they were attached to — so they are carried as objections about the card
                // as a whole, which is what the data supports.
                issues = citations.messages().stream()
                        .map(ReviewIssue::new)
                        .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
                citationFailures++;
                if (citationFailures > MAX_CITATION_REWORKS) {
                    escalated = true;
                }
            } else {
                issues = review.issues();
            }
        }

        String awaitingStatus;
        if (escalated) {
            awaitingStatus = "awaiting human decision after citation failures";
        } else if (clean) {
            awaitingStatus = "awaiting human decision";
        } else {
            awaitingStatus = "awaiting human decision after review";
        }
        String awaitingDetail = escalated ? citations.messages().toString() : null;
        return new RetrievalLoop(draftJson, attempts, reviewerApproved, awaitingStatus,
                awaitingDetail);
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

    private record CritiqueStep(boolean approved, List<ReviewIssue> issues) {
    }

    @Override
    public void approve() {
        this.decision = JobDecision.APPROVE;
    }

    @Override
    public void reject() {
        this.decision = JobDecision.REJECT;
    }

    @Override
    public String currentStatus() {
        return status;
    }

    private void publish(String jobId, JobState state, String detail) {
        statusWrites.writeStatus(jobId, state.wireName(), detail);
    }

    /**
     * The reason a step failed, not the engine's wrapper around it.
     *
     * <p>An activity failure arrives as a chain whose outermost message names the activity and the
     * retry state; the cause the operator needs — "provider unreachable" — is at the bottom. The
     * deepest non-blank message is what a caller reading {@code GET /jobs/{id}} has to see.
     */
    private String failureMessage(RuntimeException e) {
        String message = null;
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause.getMessage() != null && !cause.getMessage().isBlank()) {
                message = cause.getMessage();
            }
        }
        return message == null ? e.getClass().getSimpleName() : message;
    }
}
