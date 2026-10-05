package com.carddraft.temporal;

import java.util.List;

/**
 * The workflow's input.
 *
 * <p>{@code maxRounds} travels as an argument rather than being read from configuration inside
 * the workflow. Configuration can change between replays of the same history, and a loop whose
 * bound shifts mid-replay takes a different path than the one recorded �?" which is the failure
 * mode replay exists to prevent. Passing it in also makes the bound part of the recorded input,
 * so the history says what the budget was.
 *
 * <p>{@code documentIds} selects the retrieval branch. Empty means the supplier text came from
 * somewhere else and only facts were extracted; non-empty means the card is built from retrieved
 * fragments and every citation has to resolve. The two branches differ in more than where the
 * content came from: the retrieval branch can be sent back for a fabricated citation, and without
 * a context to check against that would mean checking nothing.
 */
public record WorkflowRequest(String jobId, String supplierText, int maxRounds,
                              String productHint, List<String> documentIds) {

    public WorkflowRequest {
        productHint = productHint == null ? "" : productHint;
        documentIds = documentIds == null ? List.of() : List.copyOf(documentIds);
    }

    /** The facts-only branch, which is what the synchronous path and the older callers use. */
    public WorkflowRequest(String jobId, String supplierText, int maxRounds) {
        this(jobId, supplierText, maxRounds, "", List.of());
    }

    /** Whether the card is built from retrieved fragments and therefore carries citations. */
    public boolean retrievesFromDocuments() {
        return !documentIds.isEmpty();
    }
}