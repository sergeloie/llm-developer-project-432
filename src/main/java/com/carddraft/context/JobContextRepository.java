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
     * The chunk a stored label names, if this job was shown it.
     *
     * <p>Scoped to the job on purpose, and that scoping is what makes fabrication simple to detect:
     * labels are allocated per job, so a label outside this job's retained set names nothing this
     * submission can be held to.
     *
     * <p>It also means the {@code NOT_IN_CONTEXT} case — a citation to a real fragment that was
     * never shown — is not reachable through this lookup, because a label belonging to another
     * job's context cannot be resolved here at all. A shared label namespace would make it
     * reachable, and {@code CitationVerifier} already handles it; this repository does not
     * pretend to.
     */
    public java.util.Optional<Long> chunkIdForReference(String jobId, String reference) {
        return jdbc.sql("SELECT chunk_id FROM job_context_chunks WHERE job_id = :jobId AND reference = :ref")
                .param("jobId", jobId)
                .param("ref", reference)
                .query(Long.class)
                .optional();
    }
}