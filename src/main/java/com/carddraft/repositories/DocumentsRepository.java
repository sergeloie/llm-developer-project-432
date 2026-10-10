package com.carddraft.repositories;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
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
     *
     * <p>The uploaded content is a column of the table and not of this record, which is the same
     * rule applied to a question of size rather than of mapping: a document row is read on every
     * state transition, and carrying the file in each of those reads would cost the upload limit
     * in memory to serve a caller that wants the metadata. {@link #contentOf} fetches it.
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
    private final JdbcTemplate template;

    public DocumentsRepository(JdbcClient jdbc, JdbcTemplate template) {
        this.jdbc = jdbc;
        this.template = template;
    }

    public record DocumentRow(
            String id,
            String filename,
            String contentSha256,
            long sizeBytes,
            String state,
            String rejectionReason,
            int chunkCount,
            Instant createdAt,
            Instant updatedAt) {}

    /**
     * {@code is_table} arrives aliased as {@code table_flag}: "table" is reserved enough in SQL to
     * be an awkward column name to map onto a record component.
     */
    public record ChunkRow(
            long id, String documentId, int ordinal, int page, String section, String text, boolean tableFlag) {}

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

    /**
     * Records the document and the bytes it arrived as.
     *
     * <p>The content is written in the same statement as the row, so a document can never be
     * observed without the file that would have to be parsed out of it.
     */
    public DocumentRow create(String id, String filename, String sha256, long sizeBytes, byte[] content) {
        jdbc.sql("""
                        INSERT INTO documents (id, filename, content_sha256, size_bytes, state, content)
                        VALUES (:id, :filename, :sha, :size, 'new', :content)
                        """)
                .param("id", id)
                .param("filename", filename)
                .param("sha", sha256)
                .param("size", sizeBytes)
                .param("content", content)
                .update();
        return findById(id).orElseThrow();
    }

    public Optional<DocumentRow> findById(String id) {
        return jdbc.sql(DOCUMENT_COLUMNS + " FROM documents WHERE id = :id")
                .param("id", id)
                .query(DocumentRow.class)
                .optional();
    }

    /**
     * The file as it was uploaded, or empty when this row predates the column.
     *
     * <p>Empty rather than null so the caller has to decide what a document with no content is,
     * which is the difference between a refusal with a reason and a parse of nothing.
     */
    public Optional<byte[]> contentOf(String id) {
        return jdbc.sql("SELECT content FROM documents WHERE id = :id")
                .param("id", id)
                .query(byte[].class)
                .optional();
    }

    public void markParsing(String id) {
        jdbc.sql("""
                        UPDATE documents
                           SET state = 'parsing', rejection_reason = NULL, updated_at = now()
                         WHERE id = :id
                        """).param("id", id).update();
    }

    /**
     * Marks the document searchable, but only once every one of its chunks has a vector.
     *
     * <p>The condition is the whole point. {@code indexed} is what tells a caller the document can be
     * *searched*, and a chunk with no vector is invisible to the vector index — which is built
     * {@code WHERE embedding IS NOT NULL} — however much text it holds. Marking a document indexed
     * before its vectors exist is how a service ends up reporting that four documents are ready and
     * retrieving from none of them.
     *
     * <p>Also the only safe way to move the state, because the check and the write are one statement.
     * A caller that asked first and wrote second would have to win a race against its own embedding
     * pass, and would eventually settle a document as indexed on the strength of a count it read
     * before the last vector landed.
     *
     * @return the document, whether or not the condition held — a document still waiting for
     *         vectors is not an error, it is a document still being indexed
     */
    public DocumentsRepository.DocumentRow markIndexedIfComplete(String id) {
        jdbc.sql("""
                        UPDATE documents d
                           SET state = 'indexed',
                               chunk_count = (SELECT count(*) FROM chunks c WHERE c.document_id = d.id),
                               rejection_reason = NULL,
                               updated_at = now()
                         WHERE d.id = :id
                           AND d.state = 'parsing'
                           AND EXISTS (SELECT 1 FROM chunks c WHERE c.document_id = d.id)
                           AND NOT EXISTS (SELECT 1 FROM chunks c
                                            WHERE c.document_id = d.id AND c.embedding IS NULL)
                        """).param("id", id).update();
        return findById(id).orElseThrow();
    }

    /**
     * Settles every parsed document whose chunks have all been embedded.
     *
     * <p>Exists because a document whose embedding failed is left at {@code parsing} with its chunks
     * stored, and the sweep that eventually embeds them runs on somebody's next restart rather than
     * in the middle of the failure. Without this the recovery would fill in the vectors and leave the
     * document still claiming to be mid-parse, which reads as a hang rather than as a success.
     *
     * @return how many documents were settled
     */
    public int settleIndexedDocuments() {
        return jdbc.sql("""
                        UPDATE documents d
                           SET state = 'indexed',
                               chunk_count = (SELECT count(*) FROM chunks c WHERE c.document_id = d.id),
                               rejection_reason = NULL,
                               updated_at = now()
                         WHERE d.state = 'parsing'
                           AND EXISTS (SELECT 1 FROM chunks c WHERE c.document_id = d.id)
                           AND NOT EXISTS (SELECT 1 FROM chunks c
                                            WHERE c.document_id = d.id AND c.embedding IS NULL)
                        """).update();
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
                        """).param("reason", reason).param("id", id).update();
        return findById(id).orElseThrow();
    }

    public void deleteChunks(String documentId) {
        jdbc.sql("DELETE FROM chunks WHERE document_id = :id")
                .param("id", documentId)
                .update();
    }

    /**
     * Writes every chunk in one round trip.
     *
     * <p>{@code JdbcClient} exposes no batch API, so the batch template carries this write the same
     * way it carries the vector writes; the reads stay on {@code JdbcClient}. A parse produces up to
     * thousands of rows, and one statement per row turns storage into a round trip per fragment.
     */
    public void insertChunks(String documentId, List<ChunkRow> chunks) {
        if (chunks.isEmpty()) {
            return;
        }
        template.batchUpdate(
                "INSERT INTO chunks (document_id, ordinal, page, section, text, is_table) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                chunks,
                chunks.size(),
                (statement, chunk) -> {
                    statement.setString(1, documentId);
                    statement.setInt(2, chunk.ordinal());
                    statement.setInt(3, chunk.page());
                    statement.setString(4, chunk.section());
                    statement.setString(5, chunk.text());
                    statement.setBoolean(6, chunk.tableFlag());
                });
    }

    public List<ChunkRow> chunksOf(String documentId) {
        return jdbc.sql(CHUNK_COLUMNS + " FROM chunks WHERE document_id = :id ORDER BY ordinal")
                .param("id", documentId)
                .query(ChunkRow.class)
                .list();
    }
}
