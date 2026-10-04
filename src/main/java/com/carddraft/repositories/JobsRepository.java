package com.carddraft.repositories;

import java.time.Instant;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The only place in the project that writes SQL about jobs.
 *
 * <p>The attempt counter is incremented by the database with {@code attempts = attempts + 1}
 * rather than read-then-written in Java. Under a redelivered message or two workers, a
 * read-then-write loses the increment, and the rewrite budget resets.
 */
@Repository
public class JobsRepository {

    private final JdbcClient jdbc;

    public JobsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Job(String id, String idempotencyKey, String status, String payload, String result,
                      int attempts, String error, Instant createdAt, Instant updatedAt) {
    }

    /**
     * Returns the existing job when the key has been seen, otherwise creates one.
     *
     * <p>The unique constraint does the real work. Checking first and then inserting would leave
     * a window where two concurrent identical requests both find nothing and both insert.
     */
    public Job createOrFindByIdempotencyKey(String idempotencyKey, String status, String payload) {
        if (idempotencyKey != null) {
            Optional<Job> existing = findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                return existing.get();
            }
        }
        String id = newJobId();
        try {
            jdbc.sql("""
                            INSERT INTO jobs (id, idempotency_key, status, payload)
                            VALUES (:id, :key, :status, CAST(:payload AS jsonb))
                            """)
                    .param("id", id)
                    .param("key", idempotencyKey)
                    .param("status", status)
                    .param("payload", payload)
                    .update();
        } catch (org.springframework.dao.DuplicateKeyException raced) {
            return findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> new IllegalStateException(
                            "job " + id + " collided with an existing idempotency key", raced));
        }
        return findById(id).orElseThrow(() -> new IllegalStateException("job " + id + " vanished after insert"));
    }

    public Optional<Job> findById(String id) {
        return jdbc.sql("SELECT * FROM jobs WHERE id = :id")
                .param("id", id)
                .query(Job.class)
                .optional();
    }

    public Optional<Job> findByIdempotencyKey(String key) {
        return jdbc.sql("SELECT * FROM jobs WHERE idempotency_key = :key")
                .param("key", key)
                .query(Job.class)
                .optional();
    }

    public void setStatus(String id, String status) {
        jdbc.sql("UPDATE jobs SET status = :status, updated_at = now() WHERE id = :id")
                .param("status", status)
                .param("id", id)
                .update();
    }

    public void recordAttempt(String id) {
        jdbc.sql("UPDATE jobs SET attempts = attempts + 1, updated_at = now() WHERE id = :id")
                .param("id", id)
                .update();
    }

    public void complete(String id, String status, String resultJson) {
        jdbc.sql("""
                        UPDATE jobs
                           SET status = :status,
                               result = CAST(:result AS jsonb),
                               updated_at = now()
                         WHERE id = :id
                        """)
                .param("status", status)
                .param("result", resultJson)
                .param("id", id)
                .update();
    }

    public void fail(String id, String error) {
        jdbc.sql("UPDATE jobs SET status = 'failed', error = :error, updated_at = now() WHERE id = :id")
                .param("error", error)
                .param("id", id)
                .update();
    }

    private String newJobId() {
        return java.util.UUID.randomUUID().toString().substring(0, 12);
    }
}
