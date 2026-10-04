package com.carddraft.temporal;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;

/**
 * The client and the worker, registered explicitly.
 *
 * <p>Not discovered. This SDK version has no workflow or activity implementation annotation for a
 * discovery pass to bind to — implementations are handed to
 * {@code registerWorkflowImplementationTypes} and {@code registerActivitiesImplementations} by
 * name — so an auto-discovery layer would have nothing to match, and would be one more thing whose
 * behaviour has to be guessed at.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "card.temporal.worker.enabled", havingValue = "true", matchIfMissing = true)
public class TemporalConfiguration {

    /**
     * Whether the process engine client and worker live in this application.
     *
     * <p>On by default, which is right for development and for a single deployment. Turned off
     * where the worker runs as its own process — and in tests, which supply an isolated in-memory
     * engine instead. That isolation matters: an embedded development server is a JVM-wide
     * resource, so several test contexts would share one, and whichever context closed first would
     * shut down the server the others were still polling — a failure with no error anywhere,
     * because a worker polling a dead server simply never hears about a workflow.
     */

    @Bean(destroyMethod = "")
    WorkflowServiceStubs workflowServiceStubs(TemporalSettings settings) {
        if (settings.isLocal()) {
            return WorkflowServiceStubs.newLocalServiceStubs();
        }
        return WorkflowServiceStubs.newServiceStubs(WorkflowServiceStubsOptions.newBuilder()
                .setTarget(settings.target())
                .build());
    }


    /**
     * No destroy method: the client holds no resources of its own — the connection belongs to the
     * stubs, whose shutdown closes it. Declaring one here would fail context startup with an
     * "invalid destruction signature".
     */
    @Bean
    WorkflowClient workflowClient(WorkflowServiceStubs service, TemporalSettings settings) {
        return WorkflowClient.newInstance(service, WorkflowClientOptions.newBuilder()
                .setNamespace(settings.namespace())
                .build());
    }

    /**
     * The worker runs inside the application rather than as a separate process.
     *
     * <p>Worth being explicit about, because the original shape of this project ran its worker
     * separately. That separation existed because a blocking step occupies a worker thread that
     * cannot be replenished, so a pool had to be sized and a second process existed to host it.
     * With virtual threads a blocking step costs a thread rather than a fixed slot, so the
     * operational reason for a second process is gone. Splitting it later is a deployment change,
     * not a code change: nothing here knows it is in the same JVM.
     */
    @Bean(destroyMethod = "shutdown")
    WorkerFactory workerFactory(WorkflowClient client, TemporalSettings settings, CardActivities activities) {
        WorkerFactory factory = WorkerFactory.newInstance(client);
        Worker worker = factory.newWorker(settings.taskQueue());
        worker.registerWorkflowImplementationTypes(CardWorkflowImpl.class);
        worker.registerActivitiesImplementations(activities);
        factory.start();
        return factory;
    }
}
