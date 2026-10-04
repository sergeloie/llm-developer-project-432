# Temporal's Java SDK has no workflow sandbox, so determinism is enforced by convention

The assignment warns that workflow code runs in a sandbox where ordinary imports are
restricted, and that an import error at worker startup almost certainly means this. That is
true of the Python SDK and does not apply here: the Java SDK does not sandbox workflow code
at all. Determinism is therefore a convention we hold ourselves, not a guarantee the
platform enforces.

The conventions, applied from the start:

- Activity timeouts are module constants, never values read from configuration, because
  configuration may change between history replays.
- No `Instant.now()`, no `Random`, no environment reads, and no network calls inside
  workflow methods. Anything with a side effect is an activity.
- Activities that block (database access, file reads, document parsing) are dispatched to an
  activity thread executor. With virtual threads on Java 21 this needs no tuning, but the
  explicit setting is kept so the intent is visible.

**Consequences**

The assignment's sandbox symptom cannot be reproduced here, so a worker that fails to start
on imports has a different cause and must be diagnosed as such. Replay correctness is
verified with the SDK's `WorkflowReplayer` against recorded histories rather than being
guaranteed by the runtime.