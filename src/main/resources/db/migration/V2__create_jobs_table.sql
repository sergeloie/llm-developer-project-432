-- Job state lives in the database rather than only in the process engine's history, because a
-- client has to be able to poll it and because it must survive the engine being restarted.
--
-- updated_at is not decorative: it is how a job left in a non-terminal state by a dead worker is
-- told apart from one that is still running. Without it, "what is stuck" can only be answered
-- by listing every intermediate status.
--
-- status is TEXT without a CHECK constraint for now, deliberately. The state machine is not
-- settled until the durable-execution work defines it, and freezing a list now would mean three
-- migrations rewriting a constraint. The enum arrives with that definition.
--
-- 'rejected' and 'failed' will be both terminal and deliberately distinct: one is a business
-- outcome, the other an incident, and merging them makes alerting and success rates impossible.
CREATE TABLE jobs (
    id              TEXT PRIMARY KEY,
    -- A client that timed out and retried must get the original job back, not a second one and
    -- a second bill. NULL when the caller supplied no key.
    idempotency_key TEXT UNIQUE,
    status          TEXT NOT NULL,
    payload         JSONB NOT NULL,
    result          JSONB,
    attempts        INTEGER NOT NULL DEFAULT 0,
    error           TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
