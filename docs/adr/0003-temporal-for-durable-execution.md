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