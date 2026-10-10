package com.carddraft.repositories;

import java.time.Instant;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The only place in the project that writes SQL about jobs.
 *
 * <p>The attempt count is written as an absolute ordinal the workflow already knows, never
 * incremented here. A redelivered activity invocation for the same attempt then writes the same
 * value instead of counting it twice — which is what would hand the rewrite budget back to a job
 * that had already spent it.
 */
@Repository
public class JobsRepository {

    /**
     * Columns named explicitly rather than {@code SELECT *}.
     *
     * <p>A star select binds the record to whatever the table happens to contain today: adding a
     * column breaks the mapping, removing one does the same, and neither is caught until a query
     * fails at runtime.
     */
    private static final String JOB_COLUMNS = """
            SELECT id, idempotency_key, status, detail, payload, result, attempts, error,
                   created_at, updated_at
            """;

    private final JdbcClient jdbc;

    private static final String STATUS_PARAM = "status";

    public JobsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Job(String id, String idempotencyKey, String status, String detail, String payload,
                      String result, int attempts, String error, Instant createdAt, Instant updatedAt) {
    }

    /**
     * Whether this call created the row or found an existing one.
     *
     * <p>The caller needs the difference: a repeated request must return the original job
     * <em>and</em> must not start a second process for it. Starting a process for a job that
     * already has one is not idempotent — it is a second charge with the same identifier.
     */
    public record JobCreation(Job job, boolean created) {
    }

    /**
     * Returns the existing job when the key has been seen, otherwise creates one.
     *
     * <p>The unique constraint does the real work. Checking first and then inserting would leave
     * a window where two concurrent identical requests both find nothing and both insert.
     */
    public JobCreation createOrFindByIdempotencyKey(String idempotencyKey, String status, String payload) {
        if (idempotencyKey != null) {
            Optional<Job> existing = findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                return new JobCreation(existing.get(), false);
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
                    .param(STATUS_PARAM, status)
                    .param("payload", payload)
                    .update();
        } catch (org.springframework.dao.DuplicateKeyException raced) {
            return new JobCreation(findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> new IllegalStateException(
                            "job " + id + " collided with an existing idempotency key", raced)), false);
        }
        return new JobCreation(
                findById(id).orElseThrow(() -> new IllegalStateException("job " + id + " vanished after insert")),
                true);
    }

    public Optional<Job> findById(String id) {
        return jdbc.sql(JOB_COLUMNS + " FROM jobs WHERE id = :id")
                .param("id", id)
                .query(Job.class)
                .optional();
    }

    /**
     * A row with a caller-chosen identifier.
     *
     * <p>The metrics harness names its jobs after the document it measures
     * ({@code metrics-kettle_spec.xlsx}), because the call records and the retained
     * context point at the job by foreign key — a random identifier would leave those
     * rows unattributable. The unique key does the racing: a repeated run finds the row
     * rather than inserting a second one.
     */
    public Job createWithId(String id, String status, String payload) {
        try {
            jdbc.sql("""
                            INSERT INTO jobs (id, status, payload)
                            VALUES (:id, :status, CAST(:payload AS jsonb))
                            """)
                    .param("id", id)
                    .param(STATUS_PARAM, status)
                    .param("payload", payload)
                    .update();
        } catch (org.springframework.dao.DuplicateKeyException raced) {
            return findById(id).orElseThrow(() -> new IllegalStateException(
                    "job " + id + " collided on re-entry", raced));
        }
        return findById(id).orElseThrow(() -> new IllegalStateException("job " + id + " vanished after insert"));
    }

    public Optional<Job> findByIdempotencyKey(String key) {
        return jdbc.sql(JOB_COLUMNS + " FROM jobs WHERE idempotency_key = :key")
                .param("key", key)
                .query(Job.class)
                .optional();
    }

    public void setStatus(String id, String status) {
        setStatus(id, status, null);
    }

    public void setStatus(String id, String status, String detail) {
        jdbc.sql("""
                        UPDATE jobs
                           SET status = :status,
                               detail = COALESCE(:detail, detail),
                               updated_at = now()
                         WHERE id = :id
                        """)
                .param(STATUS_PARAM, status)
                .param("detail", detail)
                .param("id", id)
                .update();
    }

    /**
     * Sets the attempt ordinal the workflow already knows.
     *
     * <p>An absolute set rather than an increment, because the workflow passes the ordinal it has
     * counted itself. A redelivered activity invocation carries the same ordinal and overwrites
     * with the same value, where an increment would count one attempt twice and hand the rewrite
     * budget back to a job that had spent it.
     */
    public void setAttempts(String id, int attempt) {
        jdbc.sql("UPDATE jobs SET attempts = :attempt, updated_at = now() WHERE id = :id")
                .param("attempt", attempt)
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
                .param(STATUS_PARAM, status)
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
