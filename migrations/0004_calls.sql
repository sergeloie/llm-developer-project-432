-- 0004_calls.sql: учёт вызовов модели (стоимость, токены, длительность).
CREATE TABLE IF NOT EXISTS model_calls (
    id          BIGSERIAL PRIMARY KEY,
    job_id      TEXT,
    doc_id      INTEGER REFERENCES documents(id),
    role        TEXT NOT NULL,
    model       TEXT NOT NULL,
    prompt_tokens     INTEGER NOT NULL DEFAULT 0,
    completion_tokens INTEGER NOT NULL DEFAULT 0,
    cost_kopek  INTEGER NOT NULL DEFAULT 0,
    duration_ms INTEGER NOT NULL DEFAULT 0,
    request_id  TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS model_calls_job_idx ON model_calls (job_id);
CREATE INDEX IF NOT EXISTS model_calls_request_idx ON model_calls (request_id);