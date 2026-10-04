-- Documents and the chunks cut from them.
--
-- The chunks table is created now without its vector column; that arrives with the embedding
-- step, as a separate migration, so that a failure to load a model is never confused with a
-- failure to store text.
CREATE TABLE documents (
    id             TEXT PRIMARY KEY,
    filename       TEXT NOT NULL,
    -- Content hash, not the filename: the same file under two names is one document, and a
    -- re-upload of the same file must not produce a second set of chunks.
    content_sha256 TEXT NOT NULL UNIQUE,
    size_bytes     BIGINT NOT NULL,
    state          TEXT NOT NULL,
    -- Why the document was refused. Present exactly when it was, which is the point: a rejection
    -- with no reason is indistinguishable from a parser that silently returned nothing.
    rejection_reason TEXT,
    chunk_count    INTEGER NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE chunks (
    id          BIGSERIAL PRIMARY KEY,
    document_id TEXT NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    ordinal     INTEGER NOT NULL,
    page        INTEGER NOT NULL,
    section     TEXT NOT NULL,
    text        TEXT NOT NULL,
    -- A table row is never split, and a consumer needs to know that before it assumes a chunk is
    -- a self-contained passage.
    is_table    BOOLEAN NOT NULL DEFAULT FALSE,
    UNIQUE (document_id, ordinal)
);

CREATE INDEX chunks_document_idx ON chunks (document_id);
