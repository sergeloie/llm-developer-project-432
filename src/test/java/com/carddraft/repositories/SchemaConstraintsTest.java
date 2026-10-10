package com.carddraft.repositories;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The invariants the repository code assumes, held by the database rather than by convention.
 *
 * <p>Each of these is written by a raw insert so that the constraint is the only thing that can
 * reject it. A test that went through a repository would be defeated by the repository's own
 * validation and would say nothing about whether the schema holds the line when a future writer
 * forgets to.
 */
@Testcontainers(disabledWithoutDocker = true)
class SchemaConstraintsTest {

    @Container
    static final PostgreSQLContainer DATABASE = new PostgreSQLContainer("pgvector/pgvector:pg17")
            .withDatabaseName("card")
            .withUsername("card")
            .withPassword("card");

    private static JdbcClient jdbc;

    @BeforeAll
    static void migrateOnce() {
        Flyway.configure()
                .dataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        jdbc = JdbcClient.create(
                new DriverManagerDataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword()));
    }

    @BeforeEach
    void clear() {
        jdbc.sql("DELETE FROM job_context_chunks").update();
        jdbc.sql("DELETE FROM model_calls").update();
        jdbc.sql("DELETE FROM chunks").update();
        jdbc.sql("DELETE FROM documents").update();
        jdbc.sql("DELETE FROM jobs").update();
    }

    @Test
    void aDocumentStateOutsideTheLifecycleIsRejectedByTheDatabase() {
        assertThatThrownBy(() -> insertDocument("doc-x", "archived", null))
                .as("state has four values, and a fifth is a defect rather than a new stage")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aRejectedDocumentWithNoReasonIsRejectedByTheDatabase() {
        assertThatThrownBy(() -> insertDocument("doc-x", "rejected", null))
                .as("a rejection with no reason is indistinguishable from a broken service")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aDocumentWithAReasonInANonRejectedStateIsRejectedByTheDatabase() {
        assertThatThrownBy(() -> insertDocument("doc-x", "indexed", "a reason that does not belong"))
                .as("the reason is present exactly when the state is rejected")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aNegativeChunkCountIsRejectedByTheDatabase() {
        assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO documents (id, filename, content_sha256, size_bytes, state, chunk_count)
                        VALUES ('doc-x', 'f', 's', 1, 'new', -1)
                        """).update())
                .as("a count of fragments below zero is not a state the domain has")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aNegativeSizeIsRejectedByTheDatabase() {
        assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO documents (id, filename, content_sha256, size_bytes, state)
                        VALUES ('doc-x', 'f', 's', -1, 'new')
                        """).update())
                .as("a file of a negative size is not a file")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aNegativeAttemptCountIsRejectedByTheDatabase() {
        assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO jobs (id, status, payload, attempts)
                        VALUES ('job-x', 'pending', '{}', -1)
                        """).update())
                .as("an attempt count below zero cannot be reached by incrementing")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aNegativeTokenCountIsRejectedByTheDatabase() {
        assertThatThrownBy(() -> insertModelCall(-1, 0, 0)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertModelCall(0, -1, 0)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aNegativeDurationIsRejectedByTheDatabase() {
        assertThatThrownBy(() -> insertModelCall(0, 0, -1))
                .as("a call cannot have taken less than no time")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aContextRowNamingADocumentThatDoesNotExistIsRejectedByTheDatabase() {
        jdbc.sql("""
                INSERT INTO documents (id, filename, content_sha256, size_bytes, state)
                VALUES ('doc-a', 'f', 's', 1, 'indexed')
                """).update();
        jdbc.sql("""
                INSERT INTO jobs (id, status, payload) VALUES ('job-1', 'pending', '{}')
                """).update();
        long chunkId = jdbc.sql("""
                        INSERT INTO chunks (document_id, ordinal, page, section, text)
                        VALUES ('doc-a', 0, 1, 'S', 't')
                        RETURNING id
                        """).query(Long.class).single();

        assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO job_context_chunks
                            (job_id, position, reference, chunk_id, document_id, text)
                        VALUES ('job-1', 0, 'C1', :chunkId, 'ghost', 't')
                        """).param("chunkId", chunkId).update())
                .as("the retained context may not name a document that does not exist")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void insertDocument(String id, String state, String reason) {
        jdbc.sql("""
                        INSERT INTO documents
                            (id, filename, content_sha256, size_bytes, state, rejection_reason)
                        VALUES (:id, :id, :id, 1, :state, :reason)
                        """)
                .param("id", id)
                .param("state", state)
                .param("reason", reason)
                .update();
    }

    private void insertModelCall(int inputTokens, int outputTokens, int durationMs) {
        jdbc.sql("""
                        INSERT INTO model_calls
                            (tier, model, operation, input_tokens, output_tokens, cost, duration_ms)
                        VALUES ('main', 'm', 'op', :inputTokens, :outputTokens, 0, :durationMs)
                        """)
                .param("inputTokens", inputTokens)
                .param("outputTokens", outputTokens)
                .param("durationMs", durationMs)
                .update();
    }
}
