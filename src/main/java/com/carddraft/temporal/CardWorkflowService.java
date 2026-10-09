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
 * Starts, signals and inspects workflows.
 *
 * <p>Thin on purpose. The orchestration lives in the workflow; this is transport, and it holds no
 * state of its own beyond the client's connection.
 */
@Service
public class CardWorkflowService {

    private final WorkflowClient client;
    private final TemporalSettings settings;

    public CardWorkflowService(WorkflowClient client, TemporalSettings settings) {
        this.client = client;
        this.settings = settings;
    }

    /**
     * Starts a process and returns immediately.
     *
     * <p>The job row is written before this is called. The order matters: a worker that picked up
     * the process before the row existed would find nothing to update, and the job would be
     * running with no state a client could read.
     *
     * <p>A process already started for this identifier is not an error. Two concurrent submits
     * with the same idempotency key can both pass the caller's exists-check before either process
     * is visible, and the loser must read the same answer as the winner rather than a 500. The
     * work this call asks for is already accounted for.
     */
    public void start(String workflowId, WorkflowRequest request) {
        WorkflowOptions options = WorkflowOptions.newBuilder()
                .setWorkflowId(workflowId)
                .setTaskQueue(settings.taskQueue())
                .build();
        CardWorkflow workflow = client.newWorkflowStub(CardWorkflow.class, options);
        try {
            WorkflowStub.fromTyped(workflow).start(request);
        } catch (WorkflowExecutionAlreadyStarted alreadyUnderWay) {
            // Already accounted for; see the method contract above.
        }
    }

    public String currentStatus(String workflowId) {
        return client.newWorkflowStub(CardWorkflow.class, workflowId).currentStatus();
    }

    public void approve(String workflowId) {
        client.newWorkflowStub(CardWorkflow.class, workflowId).approve();
    }

    public void reject(String workflowId) {
        client.newWorkflowStub(CardWorkflow.class, workflowId).reject();
    }

    public boolean exists(String workflowId) {
        try {
            currentStatus(workflowId);
            return true;
        } catch (WorkflowNotFoundException e) {
            return false;
        }
    }

    /**
     * The result, only if the process has already finished.
     *
     * <p>Never blocks. An unfinished process is not an error — there is simply no result yet — and
     * a client polling should be told so rather than held open waiting for minutes. Asking for the
     * result with a zero timeout answers "is it done" without the wait.
     */
    public Optional<WorkflowResult> completedResult(String workflowId) {
        CompletableFuture<WorkflowResult> pending =
                stubFor(workflowId).getResultAsync(0, TimeUnit.MILLISECONDS, WorkflowResult.class);
        if (!pending.isDone()) {
            return Optional.empty();
        }
        return Optional.ofNullable(pending.join());
    }

    /** Blocks until the process finishes. For callers that genuinely wait, such as tests. */
    public WorkflowResult awaitResult(String workflowId, Duration timeout) {
        try {
            return stubFor(workflowId).getResult(timeout.toMillis(), TimeUnit.MILLISECONDS, WorkflowResult.class);
        } catch (TimeoutException e) {
            throw new IllegalStateException("workflow " + workflowId + " did not finish within " + timeout, e);
        }
    }

    private WorkflowStub stubFor(String workflowId) {
        return WorkflowStub.fromTyped(client.newWorkflowStub(CardWorkflow.class, workflowId));
    }
}
