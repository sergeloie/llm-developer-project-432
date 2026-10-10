package com.carddraft.repositories;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.carddraft.llm.CostCalculator;
import com.carddraft.llm.ModelCallRecord;

/**
 * Reads and writes model call records.
 *
 * <p>Write failures do not propagate. A call that succeeded must not be reported as failed because
 * the bookkeeping failed: the card is already produced and re-running the job would cost a
 * generation to replace a number. The failure is logged and the call proceeds, which means the
 * totals can understate — and that is the right trade, because a missing cost row is visible in the
 * totals and a duplicated generation is not visible at all.
 */
@Repository
public class ModelCallRepository {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(ModelCallRepository.class);

    private static final String JOB_ID = "jobId";

    private final JdbcClient jdbc;

    public ModelCallRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Writes one row.
     *
     * <p>Timestamps go out as {@code OffsetDateTime} in UTC rather than as {@code Instant}. The
     * driver cannot infer a SQL type for an {@code Instant} and refuses the statement outright, and
     * because this method swallows the failure the symptom is a cost table that stays empty while
     * every call appears to have succeeded. Converting here is the one-line difference between
     * working accounting and accounting that silently records nothing.
     */
    public void write(ModelCallRecord call) {
        try {
            java.time.OffsetDateTime calledAt = call.calledAt() == null
                    ? java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC)
                    : call.calledAt().atOffset(java.time.ZoneOffset.UTC);

            jdbc.sql("""
                            INSERT INTO model_calls
                                (job_id, tier, model, operation, input_tokens, output_tokens,
                                 cost, duration_ms, called_at)
                            VALUES (:jobId, :tier, :model, :operation, :inputTokens, :outputTokens,
                                    :cost, :durationMs, :calledAt)
                            """)
                    .param(JOB_ID, call.jobId())
                    .param("tier", call.tier())
                    .param("model", call.model())
                    .param("operation", call.operation())
                    .param("inputTokens", call.inputTokens())
                    .param("outputTokens", call.outputTokens())
                    .param("cost", call.cost())
                    .param("durationMs", Math.toIntExact(call.duration().toMillis()))
                    .param("calledAt", calledAt)
                    .update();
        } catch (RuntimeException e) {
            // Deliberately swallowed. The card is already produced, and re-running the job would
            // cost a generation to replace a number. The trade is that the totals can understate -
            // and a missing row is visible in a total, while a duplicated generation is not visible
            // anywhere. The message is logged at error for the same reason it is worth logging:
            // it is the only trace that a number went missing.
            log.error("llm_call_record_failed operation={} model={} error={}",
                    call.operation(), call.model(), rootMessage(e));
        }
    }

    /** The message that names the actual fault, which is rarely on the outer exception. */
    private static String rootMessage(Throwable thrown) {
        Throwable cause = thrown;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getClass().getSimpleName() + ": " + cause.getMessage();
    }

    /** One card's calls, oldest first: the order they happened in is most of the story. */
    public List<ModelCallRecord> forJob(String jobId) {
        return jdbc.sql("""
                        SELECT job_id, tier, model, operation, input_tokens, output_tokens,
                               cost, duration_ms, called_at
                          FROM model_calls
                         WHERE job_id = :jobId
                         ORDER BY called_at, id
                        """)
                .param(JOB_ID, jobId)
                .query(CallRow.class)
                .list()
                .stream()
                .map(CallRow::toRecord)
                .toList();
    }

    /**
     * What one card cost.
     *
     * <p>A sum in the database rather than a sum in Java over {@link #forJob}: the question is asked
     * over potentially many rows, and the row-per-call rule says the aggregate is derived, never
     * stored. Deriving it here keeps both answers — the detailed list and the total — reading the
     * same rows.
     */
    public BigDecimal costOfJob(String jobId) {
        BigDecimal total = jdbc.sql("SELECT coalesce(sum(cost), 0) AS total FROM model_calls WHERE job_id = :jobId")
                .param(JOB_ID, jobId)
                .query(BigDecimal.class)
                .single();
        return total == null ? BigDecimal.ZERO.setScale(CostCalculator.SCALE) : total;
    }

    /** One row of the breakdown. */
    public record TierSpend(String tier, long calls, long inputTokens, long outputTokens, BigDecimal cost) {
    }

    /**
     * Spend grouped by tier.
     *
     * <p>The question this answers is "where did the time and money go", and answering it by model
     * name would be answering a different one: two models may share a tier, and one model may sit in
     * two tiers over time.
     */
    public List<TierSpend> breakdownByTier() {
        return jdbc.sql("""
                        SELECT tier, count(*) AS calls,
                               coalesce(sum(input_tokens), 0) AS input_tokens,
                               coalesce(sum(output_tokens), 0) AS output_tokens,
                               coalesce(sum(cost), 0) AS cost
                          FROM model_calls
                         GROUP BY tier
                         ORDER BY tier
                        """)
                .query(TierSpend.class)
                .list();
    }

    /**
     * Rows as the database returns them.
     *
     * <p>Separate from the record because the column types are not the domain types: cost is a
     * numeric, duration an integer, and the mapping belongs here rather than inside
     * {@link ModelCallRecord}, which should not know how a duration is stored.
     */
    record CallRow(String jobId, String tier, String model, String operation,
                   int inputTokens, int outputTokens, BigDecimal cost,
                   int durationMs, Instant calledAt) {

        ModelCallRecord toRecord() {
            return new ModelCallRecord(jobId, tier, model, operation, inputTokens, outputTokens,
                    cost == null ? BigDecimal.ZERO.setScale(CostCalculator.SCALE) : cost,
                    Duration.ofMillis(durationMs),
                    calledAt);
        }
    }
}