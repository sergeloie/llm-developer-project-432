CREATE TABLE IF NOT EXISTS pipeline_traces (
    id              BIGSERIAL PRIMARY KEY,
    job_id          TEXT NOT NULL,
    ordinal         INTEGER NOT NULL,
    step            TEXT NOT NULL,
    agent           TEXT,
    model           TEXT,
    prompt_text     TEXT,
    raw_completion  TEXT,
    parsed_output   JSONB,
    usage_json      JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_pipeline_traces_job ON pipeline_traces(job_id, ordinal);
