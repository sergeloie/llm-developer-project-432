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
import io.temporal.worker.WorkerFactoryOptions;
import io.temporal.worker.WorkerOptions;

/**
 * The client and the worker, registered explicitly.
 *
 * <p>Not discovered. This SDK version has no workflow or activity implementation annotation for a
 * discovery pass to bind to — implementations are handed to
 * {@code registerWorkflowImplementationTypes} and {@code registerActivitiesImplementations} by
 * name — so an auto-discovery layer would have nothing to match, and it would be one more thing whose
 * behaviour has to be guessed at.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "card.temporal.worker.enabled", havingValue = "true", matchIfMissing = true)
public class TemporalConfiguration {

    /**
     * Whether the process engine client and worker live in this application.
     *
     * <p>On by default, which is right for development and for a single deployment. Turned off
     * where the worker runs as its own process — and where the client is not needed at all, such as
     * a test that exercises parsing or retrieval and has no business talking to an engine.
     *
     * <p>The address always has to resolve to something: there is no mode in which this application
     * provides its own engine. A developer starts one with {@code docker compose up}, or with
     * {@code temporal server start-dev} on the same port.
     */

    @Bean(destroyMethod = "")
    WorkflowServiceStubs workflowServiceStubs(TemporalSettings settings) {
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
    WorkerFactory workerFactory(WorkflowClient client, TemporalSettings settings, CardActivities activities,
                               DocumentActivities documentActivities) {
        WorkerFactory factory = WorkerFactory.newInstance(client,
                WorkerFactoryOptions.newBuilder()
                        .setMaxWorkflowThreadCount(settings.maxWorkflowThreads())
                        .build());
        Worker worker = factory.newWorker(settings.taskQueue(),
                WorkerOptions.newBuilder()
                        .setMaxConcurrentActivityExecutionSize(settings.maxActivityThreads())
                        .build());
        worker.registerWorkflowImplementationTypes(CardWorkflowImpl.class, DocumentWorkflowImpl.class);
        worker.registerActivitiesImplementations(activities, documentActivities);
        factory.start();
        return factory;
    }
}
