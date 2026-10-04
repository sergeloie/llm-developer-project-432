-- Vector and word search columns for chunks.
--
-- Separate migration from the text, so a failure to load a model is never mistaken for a failure
-- to store text. Both columns are nullable: a chunk exists before it is embedded, and the
-- backfill command exists precisely because some chunks will be.
--
-- The distance metric is chosen once here and the index is built for it. cosine is the metric
-- these embedding families are trained and evaluated under; changing it later would silently
-- invalidate every index built for the old one.
ALTER TABLE chunks ADD COLUMN embedding vector(768);
ALTER TABLE chunks ADD COLUMN search_text tsvector
    GENERATED ALWAYS AS (to_tsvector('simple', coalesce(section, '') || ' ' || coalesce(text, ''))) STORED;

-- The language configuration is 'simple' rather than a natural language on purpose. Supplier text
-- is Russian full of article numbers, model names and units, and stemming them would make
-- '800 Вт' and '800 ватт' collide with things they are not. 'simple' only lowercases, which is
-- what an article-number lookup needs to work at all.
CREATE INDEX chunks_embedding_idx ON chunks
    USING hnsw (embedding vector_cosine_ops)
    WHERE embedding IS NOT NULL;

CREATE INDEX chunks_search_text_idx ON chunks
    USING gin (search_text);

-- The backfill command's driving query: chunks that have text and no vector.
CREATE INDEX chunks_unembedded_idx ON chunks (document_id, ordinal) WHERE embedding IS NULL;
