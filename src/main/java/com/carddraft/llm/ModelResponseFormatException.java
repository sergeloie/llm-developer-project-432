package com.carddraft.llm;

/**
 * The response arrived but no JSON object could be recovered from it.
 *
 * <p>Deliberately separate from a validation failure. A response that cannot be read needs
 * different handling from one that was read and found wanting, and lumping them together is how
 * a formatting problem turns into an unexplained outage.
 */
public class ModelResponseFormatException extends RuntimeException {

    public ModelResponseFormatException(String message) {
        super(message);
    }
}
