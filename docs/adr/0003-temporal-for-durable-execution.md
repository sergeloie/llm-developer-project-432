# Temporal for durable execution, and no message broker

Long work runs in Temporal workflows rather than a message broker. There is no RabbitMQ and
no Celery equivalent in this project. The broker-based approach was explicitly ruled out by
the assignment — the process engine is the one durable transport this service needs — and a
second transport for the same job would mean two implementations of the same pipeline.

The workflow layer is a thin adapter: activities call the same service methods the HTTP
handlers call. The pipeline code does not import Temporal, and adding a transport must not
change a line of it.

**Considered Options**

- *RabbitMQ or Kafka plus a worker pool* — rejected. It guarantees delivery of a message
  but knows nothing about progress inside the handler, so a worker killed mid-pipeline
  re-runs and re-pays every model call already completed. It also cannot express "wait a day
  for a human" without occupying a worker for that day.
- *Hand-rolled job state in PostgreSQL with polling* — rejected. It would make the database
  the orchestrator, and job status would duplicate workflow history.

**Consequences**

Job status is written by its own activity, never from inside the pipeline, so the domain
state and the engine history stay separate. The pipeline loop is bounded at three rounds;
the engine's own unlimited default retry policy is narrowed per activity, because by
default a failing activity repeats forever and the only real limit is an overall timeout.

**The worker runs inside the application by default, and can be switched off.** With
virtual threads a blocking step costs a thread rather than a fixed pool slot that cannot be
replenished, so the operational reason for a second process — a pool to size and a host for
it — is gone. `card.temporal.worker.enabled=false` turns the client and worker off for a
deployment that runs them elsewhere. Nothing in the workflow or the services knows which it
is, so splitting the process is a configuration change.

**The SDK directly, not the Spring Boot starter.** The starter discovers workers and
activities by annotation, and this SDK version has no workflow or activity implementation
annotation to discover — implementations are handed to `registerWorkflowImplementationTypes`
by name. An auto-discovery layer with nothing to match is one more thing whose behaviour has
to be guessed at, and it drags in a Spring Boot 2.7 BOM. Registering explicitly is fewer
moving parts and is verifiable by reading the configuration class.

**The local development server is process-wide.** An embedded server is a JVM-level resource
like the connection pool, and treating it as per-context breaks a test run: several cached
contexts each build one, and the first to close shuts down the server the others are still
polling. The symptom is silence — a worker polling a dead server never hears about a
workflow — which is why tests supply their own isolated in-memory engine instead.
