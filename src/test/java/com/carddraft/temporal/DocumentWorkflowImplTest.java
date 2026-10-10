package com.carddraft.temporal;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The document parse, in an in-memory engine.
 *
 * <p>What is worth testing here is not the arithmetic of a single step but the two decisions the
 * workflow makes about it: that the document is named by its identifier rather than by its content,
 * and that a refusal is an outcome rather than something the engine is asked to try again. Both are
 * invisible in a unit test with a mocked engine, and both were wrong in the version this replaces —
 * the dead activity took a file path, so there was nothing to name a document by.
 */
class DocumentWorkflowImplTest {

    private static final String TASK_QUEUE = "card-drafting";

    private TestWorkflowEnvironment testEnvironment;
    private RecordingDocumentActivities activities;
    private WorkflowClient client;

    @BeforeEach
    void setUp() {
        testEnvironment = TestWorkflowEnvironment.newInstance();
        activities = new RecordingDocumentActivities();

        Worker worker = testEnvironment.newWorker(TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(DocumentWorkflowImpl.class);
        worker.registerActivitiesImplementations(activities);

        client = testEnvironment.getWorkflowClient();
        testEnvironment.start();
    }

    @AfterEach
    void tearDown() {
        testEnvironment.shutdown();
    }

    @Test
    void theParseIsAskedForByIdentifierAndItsOutcomeComesBack() {
        activities.refuse = false;
        start("parse-lookup");

        DocumentActivities.DocumentResult result = resultOf("parse-lookup");

        assertThat(activities.identifiersSeen)
                .as("the file travels through the database, so only the identifier crosses here")
                .containsExactly("doc-abc123", "doc-abc123");
        assertThat(result.documentId()).isEqualTo("doc-abc123");
        assertThat(result.state()).isEqualTo("indexed");
        assertThat(result.chunkCount()).isEqualTo(7);
    }

    @Test
    void aRefusalComesBackAsAnOutcomeAndIsNotRetried() {
        activities.refuse = true;
        start("parse-refused");

        DocumentActivities.DocumentResult result = resultOf("parse-refused");

        assertThat(result.state()).isEqualTo("rejected");
        assertThat(result.reason()).containsIgnoringCase("text layer");
        assertThat(activities.calls)
                .as("a scan with no text layer will read the same way three times")
                .hasValue(1);
    }

    @Test
    void aStepThatKeepsFailingIsRetriedTheAdvertisedNumberOfTimesAndThenGivesUp() {
        activities.fail = true;
        start("parse-failing");

        assertThatThrownBy(() -> resultOf("parse-failing")).isInstanceOf(WorkflowFailedException.class);
        assertThat(activities.calls)
                .as("three attempts, which is what the activity advertises")
                .hasValue(3);
    }

    @Test
    void theSameDocumentIsNotParsedTwiceByTwoUploads() {
        activities.refuse = false;
        DocumentWorkflowService parses = new DocumentWorkflowService(client, settings());

        assertThat(parses.start("doc-abc123"))
                .as("the first upload of this content starts the work")
                .isTrue();
        assertThat(parses.start("doc-abc123"))
                .as("a second upload of the same content must not re-parse it, which would delete "
                        + "the chunks it already indexed in order to write the same ones again")
                .isFalse();

        assertThat(resultOf("parse-doc-abc123").chunkCount())
                .as("the second upload waits on the same parse rather than starting another")
                .isEqualTo(7);
        assertThat(activities.calls)
                .as("the second upload must not repeat the work either")
                .hasValue(2);
    }

    private static TemporalSettings settings() {
        return new TemporalSettings("local", "default", TASK_QUEUE, 100, 200);
    }

    @Test
    void theDocumentIsParsedAndThenIndexedRatherThanOnlyParsed() {
        activities.refuse = false;
        start("parse-index");

        DocumentActivities.DocumentResult result = resultOf("parse-index");

        assertThat(activities.calls)
                .as("the upload path used to stop after the parse, so nothing was ever searchable")
                .hasValue(2);
        assertThat(activities.steps).containsExactly("parse", "index");
        assertThat(result.state()).isEqualTo("indexed");
    }

    @Test
    void aRefusedDocumentIsNotIndexedAfterwards() {
        activities.refuse = true;
        start("parse-refused-no-index");

        DocumentActivities.DocumentResult result = resultOf("parse-refused-no-index");

        assertThat(activities.calls)
                .as("there is nothing to embed in a document the parser refused, and asking anyway "
                        + "would spend a model call proving it")
                .hasValue(1);
        assertThat(activities.steps).containsExactly("parse");
        assertThat(result.state()).isEqualTo("rejected");
    }

    private void start(String workflowId) {
        WorkflowOptions options = WorkflowOptions.newBuilder()
                .setWorkflowId(workflowId)
                .setTaskQueue(TASK_QUEUE)
                .build();
        WorkflowStub.fromTyped(client.newWorkflowStub(DocumentWorkflow.class, options))
                .start("doc-abc123");
    }

    private DocumentActivities.DocumentResult resultOf(String workflowId) {
        try {
            return WorkflowStub.fromTyped(client.newWorkflowStub(DocumentWorkflow.class, workflowId))
                    .getResult(20, TimeUnit.SECONDS, DocumentActivities.DocumentResult.class);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new AssertionError("the parse " + workflowId + " did not finish in time", e);
        }
    }

    /**
     * A recording stand-in for the step, not a mock: it holds no expectations, so it cannot fail a
     * test by disagreeing with one. What it exists to observe is the shape of the call — what
     * crossed the boundary and how often.
     */
    static class RecordingDocumentActivities implements DocumentActivities {

        /** The document identifier each invocation was asked about, in order. */
        final List<String> identifiersSeen = new ArrayList<>();
        /** The step names, in order, so the sequence can be asserted rather than just the count. */
        final List<String> steps = new ArrayList<>();

        final AtomicInteger calls = new AtomicInteger();

        volatile boolean refuse = false;
        volatile boolean fail = false;

        @Override
        public DocumentResult parse(String documentId) {
            track("parse", documentId);
            if (fail) {
                throw new IllegalStateException("the database is unreachable");
            }
            if (refuse) {
                return new DocumentResult(documentId, "rejected", 0, "no text layer in the file");
            }
            return new DocumentResult(documentId, "parsing", 0, null);
        }

        @Override
        public DocumentResult index(String documentId) {
            track("index", documentId);
            if (fail) {
                throw new IllegalStateException("the model server is unreachable");
            }
            return new DocumentResult(documentId, "indexed", 7, null);
        }

        private synchronized void track(String step, String documentId) {
            calls.incrementAndGet();
            steps.add(step);
            identifiersSeen.add(documentId);
        }
    }
}
