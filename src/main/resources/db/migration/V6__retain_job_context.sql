-- The exact fragments each job's model was shown.
--
-- Separate table rather than a column on jobs, because the retention outlives the job: a card can
-- be challenged long after the run that produced it, and verification has to answer against the
-- context as it was then. Retrieval is not reproducible — the ranking depended on the index at that
-- moment — so this cannot be recomputed on demand.
CREATE TABLE job_context_chunks (
    job_id       text        NOT NULL REFERENCES jobs (id) ON DELETE CASCADE,
    position     integer     NOT NULL,
    reference    text        NOT NULL,
    chunk_id     bigint      NOT NULL REFERENCES chunks (id) ON DELETE CASCADE,
    document_id  text        NOT NULL,
    page         integer,
    section      text,
    text         text        NOT NULL,
    PRIMARY KEY (job_id, position),
    UNIQUE (job_id, reference)
);

-- Verification asks "was this reference shown for this job" on every claim, so the lookup is by
-- job and reference rather than by position.
CREATE INDEX job_context_reference_idx ON job_context_chunks (job_id, reference);

-- The corpus-wide existence check behind NOT_IN_CONTEXT needs the chunk's own row.
CREATE INDEX job_context_chunk_idx ON job_context_chunks (chunk_id);