package com.carddraft.temporal;

import io.temporal.workflow.Workflow;

/**
 * Parses the document, then makes it findable.
 *
 * <p>The whole algorithm is two steps, so everything here exists to keep it honest across a
 * restart. Determinism rules are held by convention because the Java SDK does not sandbox workflow
 * code (ADR-0004): no clock, no randomness, no configuration reads, no network. The timeout and the
 * retry budget are constants for the same reason configuration is not read — a value that can
 * change between replays sends the same history down a different path.
 *
 * <p>Three attempts, because the failure these steps are most exposed to is a resource that is
 * legitimately not up yet — the model server during a fresh start. A refusal, by contrast, comes
 * back as a result rather than thrown, so the engine is never asked to retry work that cannot
 * succeed.
 */
public class DocumentWorkflowImpl implements DocumentWorkflow {

    private final DocumentActivities steps =
            Workflow.newActivityStub(DocumentActivities.class, StepActivityOptions.options());

    @Override
    public DocumentActivities.DocumentResult run(String documentId) {
        DocumentActivities.DocumentResult parsed = steps.parse(documentId);
        if ("rejected".equals(parsed.state())) {
            return parsed;
        }
        return steps.index(documentId);
    }
}
