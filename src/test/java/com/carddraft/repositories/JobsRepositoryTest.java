package com.carddraft.repositories;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The jobs table, against a real PostgreSQL.
 *
 * <p>Not substituted on purpose. Window functions, the JSONB column and the unique constraint
 * only behave as they will in production inside the real engine, and a mocked repository would
 * test the mock rather than the query.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)

@TestPropertySource(properties = "test.context-id=jobs-repo")
class JobsRepositoryTest {

    @Container
    static final PostgreSQLContainer DATABASE = new PostgreSQLContainer("pgvector/pgvector:pg17")
            .withDatabaseName("card")
            .withUsername("card")
            .withPassword("card");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("card.db.url", DATABASE::getJdbcUrl);
        registry.add("card.db.username", DATABASE::getUsername);
        registry.add("card.db.password", DATABASE::getPassword);
    }

    @Autowired
    JobsRepository jobs;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void clear() {
        jdbc.sql("DELETE FROM jobs").update();
    }

    @Test
    void storesAndReadsBackAJob() {
        String id = createNew("key-1", "{\"supplierText\":\"a blender\"}");

        JobsRepository.Job found = jobs.findById(id).orElseThrow();

        assertThat(found.id()).isEqualTo(id);
        assertThat(found.status()).isEqualTo("pending");
        assertThat(found.payload()).contains("a blender");
        assertThat(found.attempts()).isZero();
        assertThat(found.result()).isNull();
    }

    @Test
    void theSameIdempotencyKeyReturnsTheOriginalJobRatherThanASecond() {
        JobsRepository.JobCreation first = jobs.createOrFindByIdempotencyKey("key-2", "pending", "{}");
        JobsRepository.JobCreation second = jobs.createOrFindByIdempotencyKey("key-2", "pending", "{}");

        assertThat(second.job().id()).isEqualTo(first.job().id());
        assertThat(first.created()).isTrue();
        assertThat(second.created()).as("a repeated request must not create a second row").isFalse();
        assertThat(countJobs()).isEqualTo(1);
    }

    @Test
    void aMissingIdempotencyKeyAlwaysCreatesANewJob() {
        jobs.createOrFindByIdempotencyKey(null, "pending", "{}");
        jobs.createOrFindByIdempotencyKey(null, "pending", "{}");

        assertThat(countJobs()).isEqualTo(2);
    }

    @Test
    void theAttemptOrdinalIsWrittenAbsolutelySoARedeliveryDoesNotDoubleCount() {
        String id = createNew("key-3", "{}");

        jobs.setAttempts(id, 1);
        jobs.setAttempts(id, 1);

        assertThat(jobs.findById(id).orElseThrow().attempts())
                .as("a redelivered attempt carries the same ordinal and must overwrite, not add")
                .isEqualTo(1);
    }

    @Test
    void laterAttemptsAdvanceTheOrdinal() {
        String id = createNew("key-3b", "{}");

        jobs.setAttempts(id, 1);
        jobs.setAttempts(id, 2);

        assertThat(jobs.findById(id).orElseThrow().attempts()).isEqualTo(2);
    }

    @Test
    void changingStatusAdvancesTheLastModifiedTimestamp() throws InterruptedException {
        String id = createNew("key-4", "{}");
        Instant before = jobs.findById(id).orElseThrow().updatedAt();

        Thread.sleep(1100);
        jobs.setStatus(id, "generating", "attempt 2");

        JobsRepository.Job after = jobs.findById(id).orElseThrow();
        assertThat(after.status()).isEqualTo("generating");
        assertThat(after.detail()).isEqualTo("attempt 2");
        assertThat(Duration.between(before, after.updatedAt()).toSeconds())
                .as("updated_at is what makes a stuck job detectable at all")
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    void aFailedJobKeepsItsDraftAndAnError() {
        String id = createNew("key-5", "{}");

        jobs.complete(id, "rejected", "{\"title\":\"kept\"}");
        jobs.fail(id, "provider unreachable");

        JobsRepository.Job after = jobs.findById(id).orElseThrow();
        assertThat(after.status()).isEqualTo("failed");
        assertThat(after.error()).isEqualTo("provider unreachable");
        assertThat(after.result()).as("the rejected draft is still kept").contains("kept");
    }

    private String createNew(String key, String payload) {
        return jobs.createOrFindByIdempotencyKey(key, "pending", payload).job().id();
    }

    private int countJobs() {
        return jdbc.sql("SELECT count(*) AS c FROM jobs").query(Integer.class).single();
    }
}
