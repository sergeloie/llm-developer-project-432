-- The invariants the repository code assumes, moved into the database.
--
-- Each constraint below is one the application already relies on and currently enforces only by
-- writing the right values. A writer that forgets - a future repository, a migration, a manual
-- correction - is exactly the case these catch. Nothing here is dropped or renamed, and every
-- statement is additive, so an existing database keeps its rows.
--
-- The job status vocabulary is deliberately left unchecked. V2 states why: the state machine is not
-- settled, and the metrics harness already writes a status ("metrics_complete") outside any
-- lifecycle a constraint could name today. The document state machine is settled, so it is checked.

-- The four document states from the glossary: new, parsing, indexed, rejected. A fifth is a defect.
ALTER TABLE documents
    ADD CONSTRAINT documents_state_known
        CHECK (state IN ('new', 'parsing', 'indexed', 'rejected'));

-- A rejection carries its reason, and only a rejection carries one. An empty result without a
-- reason is indistinguishable from a broken service, and a stale reason on a live document is a
-- rejection that never happened.
ALTER TABLE documents
    ADD CONSTRAINT documents_rejection_reason_matches_state
        CHECK ((state = 'rejected') = (rejection_reason IS NOT NULL));

-- Counts and sizes are non-negative. These are written from measured values, and a negative one
-- would be a bug that a later aggregate would happily carry.
ALTER TABLE documents
    ADD CONSTRAINT documents_size_bytes_non_negative CHECK (size_bytes >= 0);

ALTER TABLE documents
    ADD CONSTRAINT documents_chunk_count_non_negative CHECK (chunk_count >= 0);

-- The attempt counter is incremented by the database and reset by nothing, so it cannot fall below
-- zero without a manual mistake.
ALTER TABLE jobs
    ADD CONSTRAINT jobs_attempts_non_negative CHECK (attempts >= 0);

-- Token counts and durations are counted from a response and a clock. None of them can be negative,
-- and a negative figure would be invisible in an average.
ALTER TABLE model_calls
    ADD CONSTRAINT model_calls_input_tokens_non_negative CHECK (input_tokens >= 0);

ALTER TABLE model_calls
    ADD CONSTRAINT model_calls_output_tokens_non_negative CHECK (output_tokens >= 0);

ALTER TABLE model_calls
    ADD CONSTRAINT model_calls_duration_ms_non_negative CHECK (duration_ms >= 0);

-- Null means the provider did not report a load time, which is not the same as zero.
ALTER TABLE model_calls
    ADD CONSTRAINT model_calls_load_ms_non_negative CHECK (load_ms IS NULL OR load_ms >= 0);

-- The retained context names the document it came from. The chunk already points at its document,
-- and deletions cascade through the chunk, so this can only fail for a writer that invented the
-- column's value rather than copying it.
ALTER TABLE job_context_chunks
    ADD CONSTRAINT job_context_chunks_document_id_fkey
        FOREIGN KEY (document_id) REFERENCES documents (id) ON DELETE CASCADE;
