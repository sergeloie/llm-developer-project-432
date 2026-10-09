package com.carddraft.routers;

import java.util.List;

import jakarta.validation.constraints.AssertTrue;

/**
 * The body of a job submission.
 *
 * @param supplierText content to work from on the facts branch; may be blank when documents
 *                     are supplied
 * @param documentIds  the documents a content manager picked. Non-empty switches the job onto
 *                     the retrieval branch, where the card is built from retrieved fragments and
 *                     every citation is verified against what the model was shown.
 * @param productHint  what the product is, used as the retrieval query. Optional because a
 *                     caller who selects documents and says nothing should still get a card —
 *                     the document names stand in for a missing hint.
 */
public record SubmitJobRequest(String supplierText, List<String> documentIds, String productHint) {

    /**
     * A job needs something to work from.
     *
     * <p>Refused rather than accepted and failed later: with neither text nor documents there is
     * nothing to work from, and a job that runs three minutes to report that is worse than a 400
     * at the door.
     */
    @AssertTrue(message = "supply supplierText, documentIds, or both - a job needs something to work from")
    public boolean isWorkable() {
        return (supplierText != null && !supplierText.isBlank())
                || (documentIds != null && !documentIds.isEmpty());
    }
}
