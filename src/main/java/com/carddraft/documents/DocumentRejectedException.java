package com.carddraft.documents;

/**
 * A document the service will not process, with a reason a person can act on.
 *
 * <p>Not an error in the incident sense. A scan with no text layer is a normal thing to be
 * sent, and the correct response is to say so.
 */
public class DocumentRejectedException extends RuntimeException {

    private final String reason;

    public DocumentRejectedException(String reason, String detail) {
        super(reason + (detail == null || detail.isBlank() ? "" : ": " + detail));
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }
}
