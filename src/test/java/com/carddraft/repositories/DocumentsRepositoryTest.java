package com.carddraft.repositories;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The document row, against a real PostgreSQL with the real migrations applied.
 *
 * <p>Constructed by hand rather than through the application context, on purpose. The subject is a
 * repository and a column; the application context brings a process engine with it, so a test about
 * whether a file survives a round trip would otherwise be unable to run at all on a machine with no
 * engine. The migrations are real, which is the half that matters — the content column arriving in a
 * migration rather than in a document class is the whole reason this round trip can be broken.
 */
@Testcontainers(disabledWithoutDocker = true)
class DocumentsRepositoryTest {

    @Container
    static final PostgreSQLContainer DATABASE = new PostgreSQLContainer("pgvector/pgvector:pg17")
            .withDatabaseName("card")
            .withUsername("card")
            .withPassword("card");

    private static JdbcClient jdbc;
    private static JdbcTemplate template;

    private DocumentsRepository documents;

    @BeforeAll
    static void migrateOnce() {
        Flyway.configure()
                .dataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
        jdbc = JdbcClient.create(dataSource);
        template = new JdbcTemplate(dataSource);
    }

    @BeforeEach
    void useAnEmptyTable() {
        jdbc.sql("DELETE FROM chunks").update();
        jdbc.sql("DELETE FROM documents").update();
        documents = new DocumentsRepository(jdbc, template);
    }

    @Test
    void theUploadedContentIsReadBackByteForByte() {
        byte[] content = "a specification, in whatever bytes the supplier sent"
                .getBytes(StandardCharsets.UTF_8);

        String id = documents.create("doc-bytes", "spec.pdf", "a".repeat(64), content.length, content).id();

        assertThat(documents.contentOf(id))
                .as("the file has to survive the request it arrived in, or nothing can parse it later")
                .contains(content);
    }

    @Test
    void contentIsAbsentRatherThanEmptyWhenTheRowHasNone() {
        String id = documents.create("doc-absent", "gone.pdf", "b".repeat(64), 7, null).id();

        assertThat(documents.contentOf(id))
                .as("empty would read as a file of no bytes, which parses to nothing without saying so")
                .isEmpty();
    }

    /** A zero vector of the width the column demands, which pgvector refuses to narrow. */
    private static final String ZERO_VECTOR =
            "[" + String.join(",", java.util.Collections.nCopies(768, "0")) + "]";

    @Test
    void aDocumentRowStillReadsBackItsMetadataWithTheFileInTheTable() {
        byte[] content = new byte[8192];

        var created = documents.create("doc-fat", "fat.pdf", "c".repeat(64), content.length, content);

        assertThat(created.sizeBytes()).isEqualTo(8192);
        assertThat(created.state()).isEqualTo("new");
        assertThat(documents.findById("doc-fat"))
                .as("the row mapping is unaffected by a column the row does not carry")
                .isPresent();
    }

    @Test
    void aDocumentBecomesIndexedOnlyOnceEveryChunkHasAVector() {
        documents.create("doc-vec", "spec.pdf", "d".repeat(64), 0, null);
        documents.markParsing("doc-vec");
        jdbc.sql("INSERT INTO chunks (document_id, ordinal, page, section, text) VALUES "
                + "('doc-vec', 1, 1, 'S', 'one'), ('doc-vec', 2, 1, 'S', 'two')").update();

        assertThat(documents.markIndexedIfComplete("doc-vec").state())
                .as("two chunks and no vectors: searchable by nothing but the word index, which is "
                        + "not what 'indexed' claims")
                .isEqualTo("parsing");

        jdbc.sql("UPDATE chunks SET embedding = CAST(:v AS vector) WHERE document_id = 'doc-vec' "
                + "AND ordinal = 1")
                .param("v", ZERO_VECTOR)
                .update();

        assertThat(documents.markIndexedIfComplete("doc-vec").state())
                .as("one vector still missing")
                .isEqualTo("parsing");

        jdbc.sql("UPDATE chunks SET embedding = CAST(:v AS vector) WHERE document_id = 'doc-vec' "
                + "AND ordinal = 2")
                .param("v", ZERO_VECTOR)
                .update();

        var indexed = documents.markIndexedIfComplete("doc-vec");
        assertThat(indexed.state()).isEqualTo("indexed");
        assertThat(indexed.chunkCount()).isEqualTo(2);
    }

    @Test
    void settlingTouchesOnlyDocumentsWhoseVectorsAreAllPresent() {
        documents.create("doc-done", "a.pdf", "e".repeat(64), 0, null);
        documents.create("doc-pending", "b.pdf", "f".repeat(64), 0, null);
        jdbc.sql("INSERT INTO chunks (document_id, ordinal, page, section, text) VALUES "
                + "('doc-done', 1, 1, 'S', 'one'), ('doc-pending', 1, 1, 'S', 'one')").update();
        jdbc.sql("UPDATE documents SET state = 'parsing'").update();
        jdbc.sql("UPDATE chunks SET embedding = CAST(:v AS vector) WHERE document_id = 'doc-done'")
                .param("v", ZERO_VECTOR)
                .update();

        assertThat(documents.settleIndexedDocuments()).isEqualTo(1);
        assertThat(documents.findById("doc-done").orElseThrow().state()).isEqualTo("indexed");
        assertThat(documents.findById("doc-pending").orElseThrow().state())
                .as("the document that still has a chunk with no vector")
                .isEqualTo("parsing");
    }

    @Test
    void aValueThatLooksLikeSqlIsStoredVerbatimRatherThanInterpreted() {
        String hostileFilename = "spec'); DROP TABLE documents; --";
        String hostileText = "800 W'; DELETE FROM chunks; --";

        documents.create("doc-hostile", hostileFilename, "9".repeat(64), 0, null);
        documents.insertChunks("doc-hostile", List.of(new DocumentsRepository.ChunkRow(
                0, "doc-hostile", 0, 1, "Power", hostileText, false)));

        assertThat(documents.findById("doc-hostile").orElseThrow().filename())
                .as("a filename that looks like SQL is a filename, not a statement")
                .isEqualTo(hostileFilename);
        assertThat(documents.chunksOf("doc-hostile"))
                .singleElement()
                .satisfies(chunk -> assertThat(chunk.text()).isEqualTo(hostileText));
    }

    @Test
    void aDocumentWithManyChunksPersistsEveryRowInOrder() {
        documents.create("doc-many", "many.pdf", "8".repeat(64), 0, null);
        List<DocumentsRepository.ChunkRow> chunks = java.util.stream.IntStream.range(0, 40)
                .mapToObj(i -> new DocumentsRepository.ChunkRow(
                        0, "doc-many", i, 1 + (i % 3), "S" + i, "fragment " + i, i % 2 == 0))
                .toList();

        documents.insertChunks("doc-many", chunks);

        assertThat(documents.chunksOf("doc-many"))
                .as("one batch writes every fragment, and reads back in ordinal order")
                .hasSize(40)
                .extracting(DocumentsRepository.ChunkRow::ordinal)
                .containsExactlyElementsOf(java.util.stream.IntStream.range(0, 40).boxed().toList());
    }

    @Test
    void returningADocumentToParsingClearsAStaleRejectionReason() {
        documents.create("doc-retry", "retry.pdf", "7".repeat(64), 0, null);
        documents.markRejected("doc-retry", "the file could not be parsed");

        documents.markParsing("doc-retry");

        DocumentsRepository.DocumentRow retrying = documents.findById("doc-retry").orElseThrow();
        assertThat(retrying.state()).isEqualTo("parsing");
        assertThat(retrying.rejectionReason())
                .as("a document being parsed again is no longer rejected, and a reason left "
                        + "behind is a rejection that never happened")
                .isNull();
    }
}