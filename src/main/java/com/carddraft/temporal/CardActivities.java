package com.carddraft.temporal;

import java.util.List;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * The steps the workflow can invoke.
 *
 * <p>Facts and drafts cross the boundary as JSON strings rather than as objects. History is
 * bounded — roughly fifty thousand events or fifty megabytes — and every input and output of every
 * step accumulates in it, so passing whole documents through arguments spends that budget
 * directly. A string also makes the history readable: what was actually sent is visible rather
 * than inferred from a serialisation format.
 */
@ActivityInterface
public interface CardActivities {

    @ActivityMethod
    String extractFacts(String jobId, String supplierText);

    @ActivityMethod
    String generateDraft(String jobId, String factsJson, List<String> issues);

    @ActivityMethod
    ReviewOutcome reviewDraft(String jobId, String factsJson, String draftJson);

    /**
     * Records a state transition.
     *
     * <p>A step of its own, not something the pipeline does from inside itself. The client polls
     * the database, so the state has to be there independently of the work that changes it —
     * otherwise a long model call leaves a job looking untouched rather than busy.
     */
    @ActivityMethod
    void writeStatus(String jobId, String state, String detail);

    @ActivityMethod
    void countAttempt(String jobId);

    @ActivityMethod
    void recordFailure(String jobId, String error);
}
