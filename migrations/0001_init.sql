-- 0001_init.sql: расширение векторов, каталог применённых версий, таблица задач.
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS schema_migrations (
    version   TEXT PRIMARY KEY,
    applied   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS jobs (
    id              TEXT PRIMARY KEY,
    idempotency_key TEXT UNIQUE,
    kind            TEXT NOT NULL DEFAULT 'generate_text',
    status          TEXT NOT NULL DEFAULT 'pending',
    payload         JSONB NOT NULL,
    result          JSONB,
    attempts        INTEGER NOT NULL DEFAULT 0,
    error           TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS jobs_status_idx ON jobs (status);
