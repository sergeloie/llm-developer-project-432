package com.carddraft.temporal;

import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * Generate a card, then wait for a person to decide.
 *
 * <p>The pause is the point. A card waiting on a content manager might wait a day, and an ordinary
 * worker would be held for that day: it could take no other work, and in a queue-based system the
 * slot stays occupied. Here the process stands on a condition that costs nothing — no thread, no
 * connection, no worker slot — so waiting an hour or a week costs the same.
 *
 * <p>A signal rather than an update because the caller needs no acknowledgement: sending a
 * decision is fire-and-forget, and the client learns the outcome by reading the job. An update
 * would be the better choice for a button that must report "the process was not in a state to
 * accept this", and that is a later question.
 */
@WorkflowInterface
public interface CardWorkflow {

    @WorkflowMethod
    WorkflowResult run(WorkflowRequest request);

    @SignalMethod
    void approve();

    @SignalMethod
    void reject();

    /** Reads where the process is, without changing it. */
    @QueryMethod
    String currentStatus();
}
