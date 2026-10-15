CREATE TABLE IF NOT EXISTS llm_calls (
    id           BIGSERIAL PRIMARY KEY,
    job_id       TEXT,
    agent        TEXT NOT NULL DEFAULT '',
    model        TEXT NOT NULL DEFAULT '',
    prompt_tokens     INTEGER,
    completion_tokens INTEGER,
    cost         NUMERIC(10, 6) NOT NULL DEFAULT 0,
    latency_ms   INTEGER,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_llm_calls_job ON llm_calls(job_id);
