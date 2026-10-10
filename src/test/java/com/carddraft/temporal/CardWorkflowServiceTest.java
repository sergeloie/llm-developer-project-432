package com.carddraft.temporal;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;

import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The transport, against an in-memory engine.
 *
 * <p>What is worth testing here is not the happy path but the race the caller cannot see: two
 * concurrent submits with one idempotency key both deciding to start, and the engine having
 * already started the process for the loser. That must read as success, not as a 5xx.
 */
class CardWorkflowServiceTest {

    private static final String TASK_QUEUE = "card-drafting";

    private TestWorkflowEnvironment environment;
    private CardWorkflowService workflows;

    @BeforeEach
    void setUp() {
        environment = TestWorkflowEnvironment.newInstance();
        Worker worker = environment.newWorker(TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(CardWorkflowImpl.class);
        worker.registerActivitiesImplementations(new CardWorkflowImplTest.RecordingActivities());
        environment.start();
        workflows = new CardWorkflowService(
                environment.getWorkflowClient(), new TemporalSettings("local", "default", TASK_QUEUE, 100, 200));
    }

    @AfterEach
    void tearDown() {
        environment.shutdown();
    }

    @Test
    void startingAWorkflowThatIsAlreadyRunningIsNotAnError() {
        WorkflowRequest request = new WorkflowRequest("job-dup", "supplier text", 3);

        workflows.start("job-dup", request);

        assertThatCode(() -> workflows.start("job-dup", request))
                .as("a concurrent duplicate submit must not surface as a 5xx")
                .doesNotThrowAnyException();
    }
}
