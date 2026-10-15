-- 0002_documents.sql: документы поставщиков и их фрагменты (индекс наполнится на шаге 6).
CREATE TABLE IF NOT EXISTS documents (
    id           INTEGER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    filename     TEXT NOT NULL,
    sha256       TEXT NOT NULL UNIQUE,  -- повторная загрузка не создаёт дублей
    size_bytes   INTEGER NOT NULL,
    status       TEXT NOT NULL DEFAULT 'new',  -- new|parsing|indexed|failed
    error        TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS chunks (
    id           INTEGER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    document_id  INTEGER NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    ord          INTEGER NOT NULL,
    page         INTEGER,
    section      TEXT,
    kind         TEXT NOT NULL DEFAULT 'text',  -- text | table
    article      TEXT,
    brand        TEXT,
    text         TEXT NOT NULL,
    UNIQUE (document_id, ord)
);

CREATE INDEX IF NOT EXISTS chunks_document_idx ON chunks (document_id);
