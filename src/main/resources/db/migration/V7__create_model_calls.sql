-- One row per model call.
--
-- The only place usage is counted. A summary computed from anywhere else would be a second answer
-- to "what did this cost", and the two would disagree; so nothing aggregates on write, and every
-- figure the service reports is a query over these rows.
--
-- job_id is nullable rather than NOT NULL: the synchronous endpoint calls the model with no job
-- behind it, and refusing to record those calls would make the table answer "what did the
-- asynchronous path cost" while looking like it answered "what did the service cost".
CREATE TABLE model_calls (
    id            BIGSERIAL PRIMARY KEY,
    job_id        TEXT REFERENCES jobs (id) ON DELETE SET NULL,

    -- Which of the two configured models, as a name rather than a string. "main" and "utility" are
    -- what the configuration and the breakdown query both speak, so a model rename does not
    -- fragment the history.
    tier          TEXT NOT NULL,
    model         TEXT NOT NULL,

    -- The operation is the method that made the call, such as draftCard or repairField:weight.
    -- Repair is a generation like any other and would otherwise be invisible in the totals.
    operation     TEXT NOT NULL,

    input_tokens  INTEGER NOT NULL,
    output_tokens INTEGER NOT NULL,

    -- NUMERIC(18,12), never a float. Token counts and per-million prices are both exact decimals,
    -- and float arithmetic on money accumulates an error that is small per call and wrong in
    -- aggregate - which is the only place a cost figure is ever used.
    --
    -- Twelve decimal places rather than the eight that first looked sufficient: prices are quoted
    -- per million tokens, so a single token costs price/1000000. At a cheap 0.01 per million that is
    -- exactly 0.00000001 - the eighth place. Any smaller scale would round a one-token call to
    -- zero, and a breakdown in which some calls cost nothing and others cost something is harder to
    -- read than one where everything is merely small.
    cost          NUMERIC(18,12) NOT NULL,

    duration_ms   INTEGER NOT NULL,

    -- The provider's own load time, when it reports one, kept beside the total. A local server that
    -- has to pull weights into memory looks identical to a slow generation otherwise, and those
    -- need different fixes. Null means the provider did not say, which is most of the time.
    load_ms       INTEGER,

    called_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The whole point of the table: what one card cost.
CREATE INDEX model_calls_job_idx ON model_calls (job_id) WHERE job_id IS NOT NULL;

-- The breakdown by tier, for the "where did the spend go" question.
CREATE INDEX model_calls_tier_idx ON model_calls (tier, called_at);

-- A completed job reports several calls in order, and reading them in call order is the common
-- case; an index on (job_id) alone leaves the sort to the planner.
CREATE INDEX model_calls_job_time_idx ON model_calls (job_id, called_at);