package com.carddraft.context;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.carddraft.repositories.ChunkSearchRepository.Hit;
import com.carddraft.repositories.JobContextRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The retained context, in a real database.
 *
 * <p>Retrieval, assembly and verification are all pure and tested as such. This exists for the one
 * thing that is not: whether the set survives the round trip, which is the property the whole
 * design rests on. A verdict computed against an in-memory context proves nothing if the stored one
 * comes back differently — and a context that lost its order or its labels would look fine until a
 * reviewer opened a citation and found the wrong fragment.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class JobContextRepositoryTest {

    @Container
    static final PostgreSQLContainer DATABASE = new PostgreSQLContainer("pgvector/pgvector:pg17")
            .withDatabaseName("card")
            .withUsername("card")
            .withPassword("card");

    @Autowired
    JobContextRepository contexts;

    @Autowired
    JdbcClient jdbc;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("card.db.url", DATABASE::getJdbcUrl);
        registry.add("card.db.username", DATABASE::getUsername);
        registry.add("card.db.password", DATABASE::getPassword);
    }

    @Test
    void aContextComesBackInTheOrderItWasGivenAndWithItsLabels() {
        seed();
        AssembledContext saved = assembled();

        contexts.save(saved);
        AssembledContext loaded = contexts.load("job-1");

        assertThat(loaded.references()).containsExactly("C1", "C2", "C3");
        assertThat(loaded.chunks()).extracting(ContextChunk::chunkId).containsExactly(101L, 102L, 103L);
        assertThat(loaded.chunks()).extracting(ContextChunk::documentId).containsExactly("doc-a", "doc-b", "doc-a");
        assertThat(loaded.chunks())
                .extracting(ContextChunk::section)
                .as("sections are stored as they were, including an absent one")
                .containsExactly("Power", "", "Care");
    }

    /**
     * The property the whole design rests on.
     *
     * <p>Verified against the record that was read back, not the one that was held in memory. That
     * distinction is the test: a verdict computed against the in-memory object would pass whether
     * or not retention works, and would then fail in production the first time a worker restarted.
     */
    @Test
    void citationsVerifyAgainstTheStoredContextAndNotOnlyTheHeldOne() {
        seed();
        contexts.save(assembled());

        AssembledContext reloaded = contexts.load("job-1");
        CitationVerifier verifier = new CitationVerifier();

        assertThat(verifier.verify(reloaded, java.util.Set.of("Power"), java.util.Map.of("Power", "C1"))
                        .isClean())
                .isTrue();
        assertThat(verifier.verify(reloaded, java.util.Set.of("Power"), java.util.Map.of("Power", "C4"))
                        .isClean())
                .as("a label that was never allocated is fabricated, even after a round trip")
                .isFalse();
    }

    /**
     * Re-running generation must not leave two contexts behind.
     *
     * <p>Two sets of labels for one job would make every reference ambiguous, and a reference that
     * resolves against the wrong set is worse than one that resolves against nothing: the reviewer
     * opens a real fragment and it supports a different claim.
     */
    @Test
    void savingAgainReplacesRatherThanAccumulates() {
        seed();
        contexts.save(assembled());

        AssembledContext second = new AssembledContext(
                "job-1", List.of(new ContextChunk("C1", 102L, "doc-b", 1, "Care", "Descale monthly")), 0, 0);
        contexts.save(second);

        AssembledContext loaded = contexts.load("job-1");

        assertThat(loaded.references()).containsExactly("C1");
        assertThat(loaded.chunks()).extracting(ContextChunk::chunkId).containsExactly(102L);
        assertThat(countRows("job-1")).isEqualTo(1);
    }

    @Test
    void aJobWithNoContextReadsBackEmptyRatherThanFailing() {
        assertThat(contexts.load("never-ran").isEmpty()).isTrue();
        assertThat(contexts.load("never-ran").references()).isEmpty();
    }

    @Test
    void oneJobsContextDoesNotLeakIntoAnothers() {
        seed();
        contexts.save(assembled());
        contexts.save(new AssembledContext(
                "job-2", List.of(new ContextChunk("C1", 103L, "doc-a", 3, "Care", "other")), 0, 0));

        assertThat(contexts.load("job-1").chunks()).hasSize(3);
        assertThat(contexts.load("job-2").chunks()).hasSize(1);
    }

    @Test
    void anEmptyContextStoresNothing() {
        seed();
        contexts.save(AssembledContext.empty("job-empty"));

        assertThat(contexts.load("job-empty").isEmpty()).isTrue();
        assertThat(countRows("job-empty")).isZero();
    }

    /**
     * Labels resolve within a job and nowhere else.
     *
     * <p>Which is the property that makes fabrication cheap to detect: a label outside this job's
     * retained set names nothing this submission can be held to, so verification needs no corpus
     * lookup and cannot accidentally resolve a citation against another job's fragments.
     */
    @Test
    void aStoredLabelResolvesWithinItsOwnJobAndNowhereElse() {
        seed();
        contexts.save(assembled());
        contexts.save(new AssembledContext(
                "job-2", List.of(new ContextChunk("C1", 103L, "doc-a", 3, "Care", "other")), 0, 0));

        assertThat(contexts.chunkIdForReference("job-1", "C2")).contains(102L);
        assertThat(contexts.chunkIdForReference("job-1", "C9"))
                .as("a label this job never used resolves to nothing")
                .isEmpty();
        assertThat(contexts.chunkIdForReference("job-3", "C1"))
                .as("and a label from another job is not visible here")
                .isEmpty();
    }

    /**
     * A label another job was shown names a real allocation.
     *
     * <p>Labels are positional — every job's first fragment is C1 — so a citation to a label no
     * context ever allocated is a hallucination, while a citation to one some other job saw is a
     * provenance failure: the fragment is real, the model was just never shown it. Citation
     * verification needs both answers, and this lookup is what provides the second.
     */
    @Test
    void aLabelAnotherJobWasShownReadsAsExistingElsewhere() {
        seed();
        contexts.save(assembled());
        contexts.save(new AssembledContext(
                "job-2", List.of(new ContextChunk("C1", 103L, "doc-a", 3, "Care", "other")), 0, 0));

        assertThat(contexts.existsReferenceInAnyContext("C2"))
                .as("allocated to job-1")
                .isTrue();
        assertThat(contexts.existsReferenceInAnyContext("C1"))
                .as("allocated to both jobs")
                .isTrue();
        assertThat(contexts.existsReferenceInAnyContext("C9"))
                .as("allocated nowhere, so a citation to it names nothing at all")
                .isFalse();
    }

    private int countRows(String jobId) {
        return jdbc.sql("SELECT count(*) FROM job_context_chunks WHERE job_id = :jobId")
                .param("jobId", jobId)
                .query(Integer.class)
                .single();
    }

    private AssembledContext assembled() {
        return new AssembledContext(
                "job-1",
                List.of(
                        new ContextChunk("C1", 101L, "doc-a", 1, "Power", "Power 800 W"),
                        new ContextChunk("C2", 102L, "doc-b", 1, "", "Weight 5.9 kg"),
                        new ContextChunk("C3", 103L, "doc-a", 3, "Care", "Descale monthly")),
                0,
                0);
    }

    private void seed() {
        jdbc.sql("DELETE FROM job_context_chunks").update();
        jdbc.sql("DELETE FROM chunks").update();
        jdbc.sql("DELETE FROM documents").update();
        for (String id : List.of("doc-a", "doc-b")) {
            jdbc.sql("""
                            INSERT INTO documents (id, filename, content_sha256, size_bytes, state)
                            VALUES (:id, :id, :hash, 10, 'indexed')
                            """).param("id", id).param("hash", "sha-" + id).update();
        }
        int ordinal = 0;
        for (Hit hit : List.of(
                new Hit(101L, "doc-a", 1, "Power", "Power 800 W", 0.1, "vector"),
                new Hit(102L, "doc-b", 1, "", "Weight 5.9 kg", 0.1, "vector"),
                new Hit(103L, "doc-a", 3, "Care", "Descale monthly", 0.1, "vector"))) {
            jdbc.sql("""
                            INSERT INTO chunks (id, document_id, ordinal, page, section, text, is_table)
                            VALUES (:id, :documentId, :ordinal, :page, :section, :text, false)
                            """)
                    .param("ordinal", ordinal++)
                    .param("id", hit.chunkId())
                    .param("documentId", hit.documentId())
                    .param("page", hit.page())
                    .param("section", hit.section())
                    .param("text", hit.text())
                    .update();
        }
        jdbc.sql("""
                INSERT INTO jobs (id, status, attempts, payload) VALUES ('job-1', 'pending', 0, '{}')
                ON CONFLICT (id) DO NOTHING
                """).update();
        jdbc.sql("""
                INSERT INTO jobs (id, status, attempts, payload) VALUES ('job-2', 'pending', 0, '{}')
                ON CONFLICT (id) DO NOTHING
                """).update();
    }
}
