package com.carddraft.temporal;

import java.util.List;

import com.carddraft.agents.ReviewIssue;

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
    String generateDraft(String jobId, String factsJson, List<ReviewIssue> issues);

    @ActivityMethod
    ReviewOutcome reviewDraft(String jobId, String factsJson, String draftJson);

    /**
     * Retrieves fragments for the chosen documents, assembles them, and retains the result.
     *
     * <p>One activity rather than three, and the retention is the reason. Retrieval, assembly and
     * writing down what was assembled have to describe the same set of fragments; splitting them
     * would mean a step boundary where the context the model was shown and the context recorded for
     * verification could come to disagree, and the verification would then be checking a set nobody
     * was shown.
     *
     * <p>Returns the rendered context together with the screening outcome rather than an
     * identifier, so the prompt the model receives is in the workflow history and a replay does
     * not depend on the chunks still being where they were — and so an escalated document
     * reaches a person with its reason instead of being generated from silently.
     *
     * @param documentIds restricts retrieval to what the caller selected; empty means every document
     */
    @ActivityMethod
    RetrievedContext retrieveAndAssemble(String jobId, String productHint, List<String> documentIds);

    @ActivityMethod
    String generateFromContext(String jobId, String contextText, List<ReviewIssue> issues);

    /**
     * Checks every citation on a draft against the fragments that were retained for this job.
     *
     * <p>Reads the context back from the database rather than taking it as an argument, on purpose.
     * The check is only worth anything if it runs against the set as it was recorded, and passing
     * the rendered text alongside would make it trivially possible to verify against a different
     * set than the one the model saw.
     */
    @ActivityMethod
    CitationCheck checkCitations(String jobId, String draftJson);

    /** The reviewer's own judgement of a card built from fragments. */
    @ActivityMethod
    ReviewOutcome reviewCardAgainstContext(String jobId, String contextText, String draftJson);

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

    /**
     * Writes the finished state and the draft in one statement.
     *
     * <p>One step rather than a status write followed by a result write, because the caller reads
     * both from the same row and two steps would leave a window in which the job says it was approved
     * and carries no card — a state a client cannot tell from a job that was approved and whose card
     * was lost.
     *
     * @param draftJson kept whether the draft was approved or rejected; a rejected draft is still
     *                  the thing a person looked at and said no to
     */
    @ActivityMethod
    void recordOutcome(String jobId, String status, String draftJson);

    @ActivityMethod
    void recordFailure(String jobId, String error);
}
