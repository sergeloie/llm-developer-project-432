package com.carddraft.temporal;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.carddraft.agents.ReviewIssue;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The workflow, in an in-memory engine.
 *
 * <p>No Docker and no server: the engine the SDK ships for tests runs the real workflow code,
 * records a real history and replays it. That is what makes these tests worth more than a unit
 * test with a mocked engine — the loop bound, the signal and the recorded history are the things
 * that actually break, and all three are exercised here.
 *
 * <p>The model client is a recording fake, so nothing is paid and nothing is awaited on a provider.
 */
class CardWorkflowImplTest {

    private TestWorkflowEnvironment testEnvironment;
    private WorkflowClient client;
    private RecordingActivities activities;

    @BeforeEach
    void setUp() {
        testEnvironment = TestWorkflowEnvironment.newInstance();
        activities = new RecordingActivities();

        Worker worker = testEnvironment.newWorker("card-drafting");
        worker.registerWorkflowImplementationTypes(CardWorkflowImpl.class);
        worker.registerActivitiesImplementations(activities);

        client = testEnvironment.getWorkflowClient();
        testEnvironment.start();
    }

    @AfterEach
    void tearDown() {
        testEnvironment.shutdown();
    }

    @Test
    void walksThroughItsStatesAndWaitsForAHuman() {
        activities.approveReview();
        String workflowId = start("job-states", 3);

        await().atMost(Duration.ofSeconds(10)).until(() -> "awaiting human decision".equals(statusOf(workflowId)));

        assertThat(activities.statuses).containsSubsequence("extracting", "generating", "reviewing", "awaiting_human");

        client.newWorkflowStub(CardWorkflow.class, workflowId).approve();

        WorkflowResult result = resultOf(workflowId);
        assertThat(result.attempts()).isEqualTo(1);
        assertThat(result.reviewerVerdict()).isTrue();
        assertThat(result.humanDecision()).isEqualTo("approve");
    }

    @Test
    void theProcessStaysCheapWhileItWaits() {
        activities.approveReview();
        String workflowId = start("job-waiting", 3);

        await().atMost(Duration.ofSeconds(10)).until(() -> "awaiting human decision".equals(statusOf(workflowId)));

        long callsWhileWaiting = activities.statuses.size();
        Duration waited = Duration.ofSeconds(2);
        await().pollDelay(waited).atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(activities.statuses)
                .as("a process standing on a condition must not keep executing steps")
                .hasSize((int) callsWhileWaiting));
        assertThat(activities.statuses).doesNotContain("approved", "rejected");
    }

    @Test
    void stopsAtTheBudgetWhenTheReviewerNeverApproves() {
        activities.alwaysRegenerate("title is longer than 60 characters");
        String workflowId = start("job-budget", 3);

        await().atMost(Duration.ofSeconds(10)).until(() -> "awaiting human decision".equals(statusOf(workflowId)));

        assertThat(activities.generateCalls).hasValue(3);
        assertThat(activities.issuesSeen.get(0))
                .as("the first round has no feedback")
                .isEmpty();
        assertThat(activities.issuesSeen.get(1))
                .as("feedback from the reviewer reaches the generator")
                .singleElement()
                .satisfies(issue -> assertThat(issue.problem()).isEqualTo("title is longer than 60 characters"));
    }

    @Test
    void aRejectionSignalFinishesTheProcessAsRejected() {
        activities.alwaysRegenerate("no good");
        String workflowId = start("job-rejected", 1);

        await().atMost(Duration.ofSeconds(10)).until(() -> "awaiting human decision".equals(statusOf(workflowId)));

        client.newWorkflowStub(CardWorkflow.class, workflowId).reject();

        WorkflowResult result = resultOf(workflowId);
        assertThat(result.humanDecision()).isEqualTo("reject");
        assertThat(activities.statuses).contains("awaiting_human");
        assertThat(activities.outcomes)
                .as("the terminal state is written by the step that also writes the card, so a "
                        + "client reading the job sees both or neither")
                .singleElement()
                .satisfies(outcome -> assertThat(outcome).startsWith("rejected:"));
    }

    @Test
    void aFailingStepEndsTheJobAsFailedAndRecordsTheError() {
        activities.failExtraction = true;
        start("job-failed", 3);

        await().atMost(Duration.ofSeconds(15)).until(() -> !activities.failures.isEmpty());

        assertThat(activities.failures)
                .as("a terminal failure must carry its reason, or GET /jobs/{id} cannot report it")
                .anySatisfy(error -> assertThat(error).contains("provider unreachable"));
        assertThat(activities.outcomes).as("nothing was approved or rejected").isEmpty();
    }

    @Test
    void theAttemptOrdinalIsWrittenByTheWorkflowRatherThanHeldInMemory() {
        activities.alwaysRegenerate("no good");
        String workflowId = start("job-attempts", 3);

        await().atMost(Duration.ofSeconds(10)).until(() -> "awaiting human decision".equals(statusOf(workflowId)));

        assertThat(activities.attemptOrdinals)
                .as("the workflow passes the ordinal it counted, so a redelivered write is idempotent")
                .containsExactly(1, 2, 3);
    }

    @Test
    void approvingAJobThatProducedNoDraftIsRefusedRatherThanRecordedAsApproved() {
        // Zero rounds is the shape an escalated or exhausted job leaves behind: it reaches the
        // human with an empty draft. An approval that arrives anyway must not become a success.
        String workflowId = start("job-no-draft", 0);

        await().atMost(Duration.ofSeconds(10)).until(() -> "awaiting human decision".equals(statusOf(workflowId)));
        client.newWorkflowStub(CardWorkflow.class, workflowId).approve();

        resultOf(workflowId);
        assertThat(activities.outcomes)
                .as("an approval with nothing to show must not be recorded as an outcome")
                .isEmpty();
        assertThat(activities.failures)
                .as("and the refusal has a reason a caller can read")
                .singleElement()
                .satisfies(error -> assertThat(error).containsIgnoringCase("no draft"));
    }

    @Test
    void theCardIsHandedBackWithTheOutcomeOnceAPersonHasDecided() {
        activities.approveReview();
        String workflowId = start("job-outcome", 3);

        await().atMost(Duration.ofSeconds(10)).until(() -> "awaiting human decision".equals(statusOf(workflowId)));
        client.newWorkflowStub(CardWorkflow.class, workflowId).approve();
        resultOf(workflowId);

        assertThat(activities.outcomes)
                .as("a decision that writes a state without the draft leaves a caller nothing to read")
                .singleElement()
                .satisfies(
                        outcome -> assertThat(outcome).startsWith("approved:").contains("draft 1"));
    }

    @Test
    void aRejectionAlsoKeepsTheDraftThePersonLookedAt() {
        activities.approveReview();
        String workflowId = start("job-rejected-outcome", 3);

        await().atMost(Duration.ofSeconds(10)).until(() -> "awaiting human decision".equals(statusOf(workflowId)));
        client.newWorkflowStub(CardWorkflow.class, workflowId).reject();
        resultOf(workflowId);

        assertThat(activities.outcomes).singleElement().satisfies(outcome -> assertThat(outcome)
                .startsWith("rejected:"));
    }

    private String start(String workflowId, int maxRounds) {
        WorkflowOptions options = WorkflowOptions.newBuilder()
                .setWorkflowId(workflowId)
                .setTaskQueue("card-drafting")
                .build();
        CardWorkflow workflow = client.newWorkflowStub(CardWorkflow.class, options);
        io.temporal.client.WorkflowStub.fromTyped(workflow)
                .start(new WorkflowRequest(workflowId, "supplier text", maxRounds));
        return workflowId;
    }

    private String statusOf(String workflowId) {
        return client.newWorkflowStub(CardWorkflow.class, workflowId).currentStatus();
    }

    private WorkflowResult resultOf(String workflowId) {
        try {
            return io.temporal.client.WorkflowStub.fromTyped(client.newWorkflowStub(CardWorkflow.class, workflowId))
                    .getResult(10, java.util.concurrent.TimeUnit.SECONDS, WorkflowResult.class);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new AssertionError("workflow " + workflowId + " did not finish in time", e);
        }
    }

    /**
     * A recording stand-in for the steps.
     *
     * <p>Records what it was asked to do and in what order, which is the only thing the workflow's
     * own behaviour consists of. It is not a mock: it holds no expectations, so it cannot fail a
     * test by disagreeing with one.
     */
    static class RecordingActivities implements CardActivities {

        final List<String> statuses = new ArrayList<>();
        final List<List<ReviewIssue>> issuesSeen = new ArrayList<>();
        final List<String> outcomes = new ArrayList<>();
        final List<String> failures = new ArrayList<>();
        final List<Integer> attemptOrdinals = new ArrayList<>();
        final AtomicInteger generateCalls = new AtomicInteger();

        volatile boolean approve = true;
        volatile boolean failExtraction = false;
        volatile String issue = "";

        void approveReview() {
            approve = true;
        }

        void alwaysRegenerate(String issue) {
            approve = false;
            this.issue = issue;
        }

        @Override
        public synchronized String extractFacts(String jobId, String supplierText) {
            statuses.add("extracting");
            if (failExtraction) {
                throw new IllegalStateException("provider unreachable");
            }
            return "{\"productName\":\"Blender\"}";
        }

        @Override
        public synchronized String generateDraft(String jobId, String factsJson, List<ReviewIssue> issues) {
            generateCalls.incrementAndGet();
            issuesSeen.add(List.copyOf(issues));
            return "{\"title\":\"draft " + generateCalls.get() + "\"}";
        }

        @Override
        public synchronized ReviewOutcome reviewDraft(String jobId, String factsJson, String draftJson) {
            return new ReviewOutcome(approve, approve ? List.of() : List.of(new ReviewIssue(issue)));
        }

        // The retrieval branch, which this test does not exercise. Answering here rather than
        // throwing keeps the facts-branch assertions about the facts branch.
        @Override
        public synchronized RetrievedContext retrieveAndAssemble(
                String jobId, String productHint, List<String> documentIds) {
            return RetrievedContext.clean("[C1] context");
        }

        @Override
        public synchronized String generateFromContext(String jobId, String contextText, List<ReviewIssue> issues) {
            return "{\"title\":\"context draft\"}";
        }

        @Override
        public synchronized ReviewOutcome reviewCardAgainstContext(String jobId, String contextText, String draftJson) {
            return new ReviewOutcome(true, List.of());
        }

        @Override
        public synchronized CitationCheck checkCitations(String jobId, String draftJson) {
            return new CitationCheck(true, List.of(), 0, 0);
        }

        @Override
        public synchronized void writeStatus(String jobId, String state, String detail) {
            statuses.add(state);
        }

        @Override
        public synchronized void countAttempt(String jobId, int attempt) {
            attemptOrdinals.add(attempt);
        }

        @Override
        public synchronized void recordOutcome(String jobId, String status, String draftJson) {
            outcomes.add(status + ":" + draftJson);
        }

        @Override
        public synchronized void recordFailure(String jobId, String error) {
            failures.add(error);
        }
    }
}
