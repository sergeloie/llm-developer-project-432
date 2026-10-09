package com.carddraft.temporal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;

import com.carddraft.agents.ReviewIssue;
import com.carddraft.context.AssembledContext;
import com.carddraft.context.CitationVerifier;
import com.carddraft.context.ContextChunk;

/**
 * The retrieval branch: retrieve, generate from fragments, and refuse a card that cites something
 * it was never shown.
 *
 * <p>In an in-memory engine, running the real workflow code against a real history. That matters
 * here more than it does elsewhere, because what is being tested is a loop and an escalation: both
 * are things that look correct in a mocked unit test and behave differently when a workflow task is
 * replayed.
 *
 * <p>The fabricated citation is scripted rather than waited for. A test that hoped a real model
 * would invent a label would pass or fail according to the model's mood, and one that faked
 * "verification passed" would prove nothing at all. Here the model is a recording fake and the
 * verifier is the real one, so the escalation is the subject and the fabrication is the input.
 */
class CardRetrievalWorkflowTest {

    private TestWorkflowEnvironment environment;
    private WorkflowClient client;
    private CitationActivities activities;

    @BeforeEach
    void startEngine() {
        environment = TestWorkflowEnvironment.newInstance();

        activities = new CitationActivities();

        Worker worker = environment.newWorker("card-drafting");
        worker.registerWorkflowImplementationTypes(CardWorkflowImpl.class);
        worker.registerActivitiesImplementations(activities);

        client = environment.getWorkflowClient();
        environment.start();
    }

    @AfterEach
    void stopEngine() {
        environment.shutdown();
    }

    /**
     * A card whose citations all resolve reaches a person.
     *
     * <p>And it must still reach a person even when everything passed: the ticket's approval step is
     * the last thing that happens, and a card that approved itself would make every other check
     * advisory.
     */
    @Test
    void aFullySupportedCardWaitsForAHumanRatherThanApprovingItself() {
        activities.cite("Power", "C1");
        String workflowId = start("job-clean", 3);

        await().atMost(Duration.ofSeconds(10))
                .until(() -> "awaiting human decision".equals(statusOf(workflowId)));

        assertThat(activities.statuses)
                .containsSubsequence("retrieving", "generating", "reviewing", "awaiting_human");

        client.newWorkflowStub(CardWorkflow.class, workflowId).approve();

        WorkflowResult result = resultOf(workflowId);
        assertThat(result.attempts()).isEqualTo(1);
        assertThat(result.reviewerVerdict()).isTrue();
        assertThat(result.humanDecision()).isEqualTo("approve");
    }

    /**
     * The case the ticket names: a citation to a fragment that was never shown.
     *
     * <p>C9 is outside a three-fragment context, so no database lookup can find it and the card
     * cannot pass. The point is that it is sent back rather than waved through — a fabricated
     * reference that only produced a warning would still reach a content manager.
     */
    @Test
    void aCitationToAFragmentThatWasNeverShownSendsTheCardBackForRework() {
        activities.cite("Power", "C9");
        String workflowId = start("job-fabricated", 3);

        await().atMost(Duration.ofSeconds(10))
                .until(() -> activities.generateFromContextCalls.get() >= 2);

        List<ReviewIssue> lastRound = activities.contextIssuesSeen.get(
                activities.contextIssuesSeen.size() - 1);
        assertThat(activities.contextIssuesSeen)
                .as("the first round has nothing to fix; the second is told which claim cited what")
                .hasSizeGreaterThanOrEqualTo(2);
        assertThat(lastRound)
                .as("and the last round is the one that carries the failure back")
                .isNotEmpty()
                .allSatisfy(issue -> assertThat(issue.asFeedback()).contains("C9"));

        client.newWorkflowStub(CardWorkflow.class, workflowId).approve();
        resultOf(workflowId);
    }

/**
 * A second fabrication goes to a person, and stops costing generations.
 *
 * <p>One rework and then a human. A model that cited three fragments correctly and invented a
 * fourth has a systematic problem, and another round produces another fabrication of the same
 * shape — so the loop stops rather than spending a generation to arrive at a card nobody trusts.
 */
    @Test
    void aSecondFabricatedCitationGoesToAHumanInsteadOfTryingAgain() {
        activities.cite("Power", "C9");
        String workflowId = start("job-repeat", 5);

        await().atMost(Duration.ofSeconds(10))
                .until(() -> "awaiting human decision after citation failures"
                        .equals(statusOf(workflowId)));

        assertThat(activities.generateFromContextCalls.get())
                .as("one attempt, one rework, and then a person")
                .isEqualTo(2);

        client.newWorkflowStub(CardWorkflow.class, workflowId).approve();
        WorkflowResult result = resultOf(workflowId);
        assertThat(result.humanDecision()).isEqualTo("approve");
    }

    /**
     * A reviewer's objection is answered before citations are checked.
     *
     * <p>Order matters: sending a card back for an attribution problem the model can actually fix is
     * cheaper and more useful than a mechanical failure it will resolve the same way twice.
     */
    @Test
    void aReviewersObjectionIsCarriedIntoTheNextAttempt() {
        activities.rejectReviewWith("the description promises quiet operation and no fragment says so");
        String workflowId = start("job-reviewer", 3);

        await().atMost(Duration.ofSeconds(10))
                .until(() -> activities.generateFromContextCalls.get() >= 2);

        assertThat(activities.contextIssuesSeen)
                .as("the reviewer's words reach the model, not just a rejection flag")
                .isNotEmpty()
                .anySatisfy(round -> assertThat(round)
                        .anyMatch(issue -> issue.asFeedback().contains("quiet operation")));

        client.newWorkflowStub(CardWorkflow.class, workflowId).approve();
        resultOf(workflowId);
    }

    /**
     * A document that is mostly attack waits for a person without spending a generation.
     *
     * <p>Escalation happens before the loop rather than inside it: generating from the
     * survivors would produce a card whose omissions nobody chose, and a card with unchosen
     * omissions reads as complete.
     */
    @Test
    void anEscalatedDocumentWaitsForAHumanWithoutGenerating() {
        activities.escalateRetrieval("3 fragments carried injected instructions, above the budget of 2");
        String workflowId = start("job-escalated", 3);

        await().atMost(Duration.ofSeconds(10))
                .until(() -> "awaiting human decision after trust escalation"
                        .equals(statusOf(workflowId)));

        assertThat(activities.generateFromContextCalls.get())
                .as("no generation runs on an escalated document")
                .isZero();
        assertThat(activities.statuses)
                .containsSubsequence("retrieving", "awaiting_human");

        // An approval that reaches the workflow another way than the guarded endpoint must not
        // record a success with an empty draft: there was never a card to approve.
        client.newWorkflowStub(CardWorkflow.class, workflowId).approve();
        resultOf(workflowId);
        assertThat(activities.failures)
                .as("the workflow refuses the approval and records why")
                .singleElement()
                .satisfies(error -> assertThat(error).containsIgnoringCase("no draft"));
    }

    /** The hints and documents reach retrieval rather than being dropped on the floor. */
    @Test
    void theChosenDocumentsAndHintAreHandedToRetrieval() {
        activities.cite("Power", "C1");
        String workflowId = start("job-docs", 3);

        await().atMost(Duration.ofSeconds(10))
                .until(() -> activities.retrieveCalls.get() >= 1);

        assertThat(activities.hintsSeen)
                .containsExactly("a 1.7 litre kettle");
        assertThat(activities.documentIdsSeen)
                .as("retrieval is narrowed to what the caller chose, or the card cites the wrong document")
                .containsExactly(List.of("doc-kettle"));

        client.newWorkflowStub(CardWorkflow.class, workflowId).approve();
        resultOf(workflowId);
    }

    private String start(String workflowId, int maxRounds) {
        WorkflowOptions options = WorkflowOptions.newBuilder()
                .setWorkflowId(workflowId)
                .setTaskQueue("card-drafting")
                .build();
        CardWorkflow workflow = client.newWorkflowStub(CardWorkflow.class, options);
        io.temporal.client.WorkflowStub.fromTyped(workflow).start(new WorkflowRequest(
                workflowId, "", maxRounds, "a 1.7 litre kettle", List.of("doc-kettle")));
        return workflowId;
    }

    private String statusOf(String workflowId) {
        return client.newWorkflowStub(CardWorkflow.class, workflowId).currentStatus();
    }

    private WorkflowResult resultOf(String workflowId) {
        try {
            return io.temporal.client.WorkflowStub
                    .fromTyped(client.newWorkflowStub(CardWorkflow.class, workflowId))
                    .getResult(10, java.util.concurrent.TimeUnit.SECONDS, WorkflowResult.class);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new AssertionError("workflow " + workflowId + " did not finish in time", e);
        }
    }

    /**
     * Steps whose citation behaviour each test chooses.
     *
     * <p>Not a mock: it holds no expectations and so cannot fail a test by disagreeing with one. It
     * records what it was asked and answers with what the test asked for — which is the whole of
     * what the workflow's own behaviour consists of.
     */
    static class CitationActivities implements CardActivities {

        /** What retrieval assembled and retained, in the shape verification reads it back. */
        private static final AssembledContext SHOWN = new AssembledContext("job", List.of(
                new ContextChunk("C1", 101L, "doc-kettle", 1, "Characteristics", "Capacity 1.7 l"),
                new ContextChunk("C2", 102L, "doc-kettle", 1, "Characteristics", "Power 2200 W"),
                new ContextChunk("C3", 103L, "doc-kettle", 2, "Care", "Descale monthly")), 0, 0);

        private final CitationVerifier verifier = new CitationVerifier();

        final List<String> statuses = new ArrayList<>();
        final List<String> failures = new ArrayList<>();
        final List<List<ReviewIssue>> contextIssuesSeen = new ArrayList<>();
        final List<String> hintsSeen = new ArrayList<>();
        final List<List<String>> documentIdsSeen = new ArrayList<>();
        final AtomicInteger generateFromContextCalls = new AtomicInteger();
        final AtomicInteger retrieveCalls = new AtomicInteger();

        private volatile String citation = "C1";
        private volatile String reviewerIssue;
        private volatile String escalationReason;

        void cite(String characteristic, String reference) {
            this.citation = reference;
        }

        void escalateRetrieval(String reason) {
            this.escalationReason = reason;
        }

        void rejectReviewWith(String issue) {
            this.reviewerIssue = issue;
        }

        @Override
        public synchronized String extractFacts(String jobId, String supplierText) {
            return "{\"productName\":\"Kettle\"}";
        }

        @Override
        public synchronized String generateDraft(String jobId, String factsJson, List<ReviewIssue> issues) {
            return "{\"title\":\"facts branch\"}";
        }

        @Override
        public synchronized ReviewOutcome reviewDraft(String jobId, String factsJson, String draftJson) {
            return new ReviewOutcome(true, List.of());
        }

        @Override
        public synchronized RetrievedContext retrieveAndAssemble(String jobId, String productHint,
                                                                 List<String> documentIds) {
            retrieveCalls.incrementAndGet();
            hintsSeen.add(productHint);
            documentIdsSeen.add(List.copyOf(documentIds));
            if (escalationReason != null) {
                return new RetrievedContext("", List.of("C1", "C2", "C3"), List.of(),
                        true, escalationReason);
            }
            return RetrievedContext.clean("[C1] Characteristics — Capacity 1.7 l");
        }

        @Override
        public synchronized String generateFromContext(String jobId, String contextText,
                                                       List<ReviewIssue> issues) {
            generateFromContextCalls.incrementAndGet();
            contextIssuesSeen.add(List.copyOf(issues));
            return "{\"title\":\"kettle\",\"sources\":{\"Power\":\"" + citation + "\"}}";
        }

        @Override
        public synchronized ReviewOutcome reviewCardAgainstContext(String jobId, String contextText,
                                                                   String draftJson) {
            return new ReviewOutcome(reviewerIssue == null, reviewerIssue == null
                    ? List.of() : List.of(new ReviewIssue("Power", reviewerIssue)));
        }

        /**
         * Verifies with the real verifier against the real retained set.
         *
         * <p>Deliberately not a stubbed verdict. A fake that returned "clean" or "dirty" on
         * command would let the escalation be tested against an assertion the test itself wrote,
         * and the thing being tested here is precisely whether the verdict reaches the loop.
         */
        @Override
        public synchronized CitationCheck checkCitations(String jobId, String draftJson) {
            CitationVerifier.Verdict verdict = verifier.verify(SHOWN, Set.of("Power"),
                    Map.of("Power", citation));
            return new CitationCheck(verdict.isClean(), verdict.messages(),
                    verdict.fabricated().size(), 0);
        }

        @Override
        public synchronized void writeStatus(String jobId, String state, String detail) {
            statuses.add(state);
        }

        @Override
        public void countAttempt(String jobId, int attempt) {
        }

        @Override
        public void recordOutcome(String jobId, String status, String draftJson) {
        }

        @Override
        public synchronized void recordFailure(String jobId, String error) {
            failures.add(error);
        }
    }
}