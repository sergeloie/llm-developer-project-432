package com.carddraft.temporal;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.stereotype.Service;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowNotFoundException;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;

/**
 * Starts a document's parse and reports on it.
 *
 * <p>Thin for the same reason {@link CardWorkflowService} is: the orchestration is in the workflow
 * and this is transport. What it does own is the identifier, because it is what makes the step
 * happen once.
 */
@Service
public class DocumentWorkflowService {

    /**
     * The identifier a parse runs under, derived from the document.
     *
     * <p>Content decides document identity, so deriving the workflow identifier from it means the
     * same file cannot be parsed twice by two uploads — which is the point of the identifier, since
     * re-parsing a document that is already indexed would delete its chunks and write them again
     * for no gain a caller could observe.
     */
    private static final String WORKFLOW_ID_PREFIX = "parse-";

    private final WorkflowClient client;
    private final TemporalSettings settings;

    public DocumentWorkflowService(WorkflowClient client, TemporalSettings settings) {
        this.client = client;
        this.settings = settings;
    }

    /**
     * Starts the parse and returns at once.
     *
     * <p>The row is written before this is called, so the parse can never begin for a document a
     * caller cannot look up.
     *
     * <p>A parse already under way, or already finished, is not an error: it means the work this
     * call asks for is accounted for, and starting a second would throw away chunks in order to
     * produce the same ones. Reported rather than hidden, because a caller that watches for the
     * document to reach a state needs to know it will not be moving.
     */
    public boolean start(String documentId) {
        WorkflowOptions options = WorkflowOptions.newBuilder()
                .setWorkflowId(workflowIdFor(documentId))
                .setTaskQueue(settings.taskQueue())
                .build();
        try {
            WorkflowStub.fromTyped(client.newWorkflowStub(DocumentWorkflow.class, options))
                    .start(documentId);
            return true;
        } catch (WorkflowExecutionAlreadyStarted alreadyUnderWay) {
            return false;
        }
    }

    /** The outcome, only if the parse has already finished. Never blocks. */
    public Optional<DocumentActivities.DocumentResult> completedResult(String documentId) {
        CompletableFuture<DocumentActivities.DocumentResult> pending = stubFor(documentId)
                .getResultAsync(0, TimeUnit.MILLISECONDS, DocumentActivities.DocumentResult.class);
        if (!pending.isDone()) {
            return Optional.empty();
        }
        DocumentActivities.DocumentResult result = pending.join();
        return result == null ? Optional.empty() : Optional.of(result);
    }

    /** Blocks until the parse finishes. For callers that genuinely wait, such as tests. */
    public DocumentActivities.DocumentResult awaitResult(String documentId, Duration timeout) {
        try {
            return stubFor(documentId).getResult(timeout.toMillis(), TimeUnit.MILLISECONDS,
                    DocumentActivities.DocumentResult.class);
        } catch (TimeoutException e) {
            throw new IllegalStateException(
                    "the parse of document " + documentId + " did not finish within " + timeout, e);
        }
    }

    public boolean exists(String documentId) {
        try {
            stubFor(documentId).getResultAsync(0, TimeUnit.MILLISECONDS,
                    DocumentActivities.DocumentResult.class);
            return true;
        } catch (WorkflowNotFoundException notStarted) {
            return false;
        }
    }

    private WorkflowStub stubFor(String documentId) {
        return WorkflowStub.fromTyped(
                client.newWorkflowStub(DocumentWorkflow.class, workflowIdFor(documentId)));
    }

    private static String workflowIdFor(String documentId) {
        return WORKFLOW_ID_PREFIX + documentId;
    }
}