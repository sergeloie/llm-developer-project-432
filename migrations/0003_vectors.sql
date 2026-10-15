-- 0003_vectors.sql: векторная колонка, лексический поиск и индексы под них.
-- Метрика — cosine, выбирается один раз: индекс строится под неё.

ALTER TABLE chunks ADD COLUMN IF NOT EXISTS embedding vector(768);

CREATE TABLE IF NOT EXISTS chunk_embs (
    chunk_id  INTEGER PRIMARY KEY REFERENCES chunks(id) ON DELETE CASCADE,
    model     TEXT NOT NULL,
    dim       INTEGER NOT NULL,
    vector    vector(768) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Гибридный поиск: лексическая колонка (russian + english), GIN-индекс.
ALTER TABLE chunks ADD COLUMN IF NOT EXISTS tsv tsvector
    GENERATED ALWAYS AS (
        to_tsvector('russian', coalesce(text, ''))
    ) STORED;

CREATE INDEX IF NOT EXISTS chunks_tsv_idx ON chunks USING GIN (tsv);

-- Приближённый векторный индекс: cosine distance на колонке chunks.embedding.
CREATE INDEX IF NOT EXISTS chunks_embedding_hnsw_idx
    ON chunks USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);

-- Зеркальный индекс (на случай внешней таблицы chunk_embs) — оставлен для совместимости.
CREATE INDEX IF NOT EXISTS chunk_embs_hnsw_idx
    ON chunk_embs USING hnsw (vector vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);
