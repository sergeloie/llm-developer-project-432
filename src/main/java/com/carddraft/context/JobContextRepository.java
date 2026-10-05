package com.carddraft.context;

import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Retains and reads back the context a job's model was shown.
 *
 * <p>Writes the chunks individually rather than as one batch because a job's context is small —
 * twelve rows — and a per-row insert lets a partial failure leave a visible count rather than
 * nothing at all.
 *
 * <p>Replaces any previous context for the job on write, so re-running a generation does not
 * accumulate two sets of labels. Two contexts for one job would make every reference ambiguous, and
 * a reference that resolves against the wrong one is worse than one that resolves against nothing.
 */
@Repository
public class JobContextRepository {

    private final JdbcClient jdbc;

    public JobContextRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void save(AssembledContext context) {
        jdbc.sql("DELETE FROM job_context_chunks WHERE job_id = :jobId")
                .param("jobId", context.jobId())
                .update();

        for (int i = 0; i < context.chunks().size(); i++) {
            ContextChunk chunk = context.chunks().get(i);
            jdbc.sql("""
                            INSERT INTO job_context_chunks
                                (job_id, position, reference, chunk_id, document_id, page, section, text)
                            VALUES (:jobId, :position, :reference, :chunkId, :documentId, :page, :section, :text)
                            """)
                    .param("jobId", context.jobId())
                    .param("position", i)
                    .param("reference", chunk.reference())
                    .param("chunkId", chunk.chunkId())
                    .param("documentId", chunk.documentId())
                    .param("page", chunk.page())
                    .param("section", chunk.section())
                    .param("text", chunk.text())
                    .update();
        }
    }

    public AssembledContext load(String jobId) {
        List<ContextChunk> chunks = jdbc.sql("""
                        SELECT reference, chunk_id, document_id, page, section, text
                          FROM job_context_chunks
                         WHERE job_id = :jobId
                         ORDER BY position
                        """)
                .param("jobId", jobId)
                .query(ContextChunk.class)
                .list();

        return new AssembledContext(jobId, chunks, 0, 0);
    }

    /**
     * Whether a chunk exists anywhere in the corpus, regardless of whether it was shown.
     *
     * <p>Exists to tell a fabricated label apart from an invented one. A label pointing at a real
     * fragment the model never saw is a provenance failure and points at the context; a label
     * pointing at nothing is a hallucination and points at the model.
     */
    public boolean chunkExists(long chunkId) {
        return Boolean.TRUE.equals(jdbc.sql("SELECT 1 FROM chunks WHERE id = :id")
                .param("id", chunkId)
                .query(Integer.class)
                .optional()
                .map(found -> 1)
                .orElse(null));
    }

    /** By reference label, for the existence check above: which chunk, if any, does this name. */
    public java.util.Optional<Long> chunkIdForReference(String jobId, String reference) {
        return jdbc.sql("SELECT chunk_id FROM job_context_chunks WHERE job_id = :jobId AND reference = :ref")
                .param("jobId", jobId)
                .param("ref", reference)
                .query(Long.class)
                .optional();
    }
}