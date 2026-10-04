package com.carddraft.repositories;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The only place that writes SQL about documents and chunks.
 */
@Repository
public class DocumentsRepository {

    /**
     * Columns named explicitly rather than {@code SELECT *}.
     *
     * <p>A star select binds the record to whatever the table happens to contain today: adding a
     * column breaks the mapping, removing one does the same, and neither is caught until a query
     * fails at runtime. Naming them also makes the shape of the record visible in the query.
     */
    private static final String DOCUMENT_COLUMNS = """
            SELECT id, filename, content_sha256, size_bytes, state, rejection_reason, chunk_count,
                   created_at, updated_at
            """;

    /**
     * {@code is_table} arrives aliased, because a bare {@code table} column maps awkwardly onto a
     * record component and reads worse in every query that selects it.
     */
    private static final String CHUNK_COLUMNS = """
            SELECT id, document_id, ordinal, page, section, text, is_table AS table_flag
            """;

    private final JdbcClient jdbc;


    public DocumentsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record DocumentRow(String id, String filename, String contentSha256, long sizeBytes,
                              String state, String rejectionReason, int chunkCount,
                              Instant createdAt, Instant updatedAt) {
    }

        /** {@code is_table} arrives aliased as {@code table_flag}: "table" is reserved enough in SQL
     *  to be an awkward column name to map onto a record component. */
    public record ChunkRow(long id, String documentId, int ordinal, int page, String section,
                           String text, boolean tableFlag) {
    }

    /**
     * Finds the document with this content, if it has been seen before.
     *
     * <p>Keyed on content rather than filename: re-uploading the same file must not double the
     * chunks, and a caller who renames a file has still sent the same document.
     */
    public Optional<DocumentRow> findByContentHash(String sha256) {
        return jdbc.sql(DOCUMENT_COLUMNS + " FROM documents WHERE content_sha256 = :hash")
                .param("hash", sha256)
                .query(DocumentRow.class)
                .optional();
    }


    public DocumentRow create(String id, String filename, String sha256, long sizeBytes) {
        jdbc.sql("""
                        INSERT INTO documents (id, filename, content_sha256, size_bytes, state)
                        VALUES (:id, :filename, :sha, :size, 'new')
                        """)
                .param("id", id)
                .param("filename", filename)
                .param("sha", sha256)
                .param("size", sizeBytes)
                .update();
        return findById(id).orElseThrow();
    }

    public Optional<DocumentRow> findById(String id) {
        return jdbc.sql(DOCUMENT_COLUMNS + " FROM documents WHERE id = :id")
                .param("id", id)
                .query(DocumentRow.class)
                .optional();
    }


    public void markParsing(String id) {
        jdbc.sql("UPDATE documents SET state = 'parsing', updated_at = now() WHERE id = :id")
                .param("id", id)
                .update();
    }

    public DocumentsRepository.DocumentRow markIndexed(String id, int chunkCount) {
        jdbc.sql("""
                        UPDATE documents
                           SET state = 'indexed', chunk_count = :count, rejection_reason = NULL,
                               updated_at = now()
                         WHERE id = :id
                        """)
                .param("count", chunkCount)
                .param("id", id)
                .update();
        return findById(id).orElseThrow();
    }

    /**
     * Refuses the document with a reason.
     *
     * <p>The chunk count is set to zero explicitly rather than left alone, so a document that was
     * partially written before failing does not report a count it does not have.
     */
    public DocumentsRepository.DocumentRow markRejected(String id, String reason) {
        jdbc.sql("""
                        UPDATE documents
                           SET state = 'rejected', rejection_reason = :reason, chunk_count = 0,
                               updated_at = now()
                         WHERE id = :id
                        """)
                .param("reason", reason)
                .param("id", id)
                .update();
        return findById(id).orElseThrow();
    }

    public void deleteChunks(String documentId) {
        jdbc.sql("DELETE FROM chunks WHERE document_id = :id").param("id", documentId).update();
    }

    /**
     * Written in a loop rather than as a JDBC batch.
     *
     * <p>JdbcClient has no batch statement, and reaching for NamedParameterJdbcTemplate to get one
     * would mean two JDBC abstractions in the repository layer for a few hundred rows. Batching
     * earns its keep when vectors are written, which is hundreds of times these rows.
     */
    public void insertChunks(String documentId, List<ChunkRow> chunks) {
        for (ChunkRow chunk : chunks) {
            jdbc.sql("""
                            INSERT INTO chunks (document_id, ordinal, page, section, text, is_table)
                            VALUES (:documentId, :ordinal, :page, :section, :text, :table)
                            """)
                    .param("documentId", documentId)
                    .param("ordinal", chunk.ordinal())
                    .param("page", chunk.page())
                    .param("section", chunk.section())
                    .param("text", chunk.text())
                    .param("table", chunk.tableFlag())

                    .update();
        }
    }

    public List<ChunkRow> chunksOf(String documentId) {
        return jdbc.sql(CHUNK_COLUMNS + " FROM chunks WHERE document_id = :id ORDER BY ordinal")
                .param("id", documentId)
                .query(ChunkRow.class)
                .list();
    }
}
