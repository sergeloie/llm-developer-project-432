package com.carddraft.temporal;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * Parses one document.
 *
 * <p>A workflow around a single step, and the reason is durability rather than orchestration: the
 * step outlives the request that asked for it. An in-process executor would lose a half-finished
 * parse to a restart and leave the document in {@code parsing} with nothing coming back for it,
 * whereas here the engine re-delivers the step to whichever worker comes back.
 */
@WorkflowInterface
public interface DocumentWorkflow {

    @WorkflowMethod
    DocumentActivities.DocumentResult run(String documentId);
}