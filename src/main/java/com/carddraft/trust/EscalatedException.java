package com.carddraft.trust;

/**
 * A document that needs a person rather than another generation.
 *
 * <p>Thrown when so many fragments carry injected instructions that the document as a whole
 * cannot be trusted, and generating from the survivors would produce a card whose omissions
 * nobody chose. The message carries the screening summary, which is already free of original
 * contact values, so it is safe to log and to store on the job.
 */
public class EscalatedException extends RuntimeException {

    public EscalatedException(String reason) {
        super(reason);
    }
}
