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
        JobsRepository.Job created = jobs.createOrFindByIdempotencyKey(
                "key-1", "pending", "{\"supplierText\":\"a blender\"}");

        JobsRepository.Job found = jobs.findById(created.id()).orElseThrow();

        assertThat(found.id()).isEqualTo(created.id());
        assertThat(found.status()).isEqualTo("pending");
        assertThat(found.payload()).contains("a blender");
        assertThat(found.attempts()).isZero();
        assertThat(found.result()).isNull();
    }

    @Test
    void theSameIdempotencyKeyReturnsTheOriginalJobRatherThanASecond() {
        JobsRepository.Job first = jobs.createOrFindByIdempotencyKey("key-2", "pending", "{}");
        JobsRepository.Job second = jobs.createOrFindByIdempotencyKey("key-2", "pending", "{}");

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(countJobs()).isEqualTo(1);
    }

    @Test
    void aMissingIdempotencyKeyAlwaysCreatesANewJob() {
        jobs.createOrFindByIdempotencyKey(null, "pending", "{}");
        jobs.createOrFindByIdempotencyKey(null, "pending", "{}");

        assertThat(countJobs()).isEqualTo(2);
    }

    @Test
    void theAttemptCounterIsIncrementedByTheDatabase() {
        JobsRepository.Job job = jobs.createOrFindByIdempotencyKey("key-3", "pending", "{}");

        jobs.recordAttempt(job.id());
        jobs.recordAttempt(job.id());

        assertThat(jobs.findById(job.id()).orElseThrow().attempts()).isEqualTo(2);
    }

    @Test
    void changingStatusAdvancesTheLastModifiedTimestamp() throws InterruptedException {
        JobsRepository.Job job = jobs.createOrFindByIdempotencyKey("key-4", "pending", "{}");
        Instant before = jobs.findById(job.id()).orElseThrow().updatedAt();

        Thread.sleep(1100);
        jobs.setStatus(job.id(), "generating");

        JobsRepository.Job after = jobs.findById(job.id()).orElseThrow();
        assertThat(after.status()).isEqualTo("generating");
        assertThat(Duration.between(before, after.updatedAt()).toSeconds())
                .as("updated_at is what makes a stuck job detectable at all")
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    void aRejectedJobKeepsItsDraftAndAnError() {
        JobsRepository.Job job = jobs.createOrFindByIdempotencyKey("key-5", "pending", "{}");

        jobs.complete(job.id(), "rejected", "{\"title\":\"kept\"}");
        jobs.fail(job.id(), "provider unreachable");

        JobsRepository.Job after = jobs.findById(job.id()).orElseThrow();
        assertThat(after.status()).isEqualTo("failed");
        assertThat(after.error()).isEqualTo("provider unreachable");
        assertThat(after.result()).as("the rejected draft is still kept").contains("kept");
    }

    private int countJobs() {
        return jdbc.sql("SELECT count(*) AS c FROM jobs").query(Integer.class).single();
    }
}
