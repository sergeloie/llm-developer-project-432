package com.carddraft.llm;

/**
 * The model finished without producing any answer at all.
 *
 * <p>Measured on real models, not hypothetical: two candidates on the development machine spent
 * their entire token budget on internal reasoning and returned an empty content field. Reported
 * as a parse failure this would be actively misleading — the JSON was never the problem — so it
 * gets its own type, naming the model and the finish reason so the cause is at least visible.
 *
 * <p>Never retried. The behaviour is deterministic for a given input, so a retry reproduces it
 * and pays for the privilege.
 */
public class EmptyModelResponseException extends RuntimeException {

    private final String model;
    private final String finishReason;

    public EmptyModelResponseException(String model, String finishReason, String operation) {
        super("model '" + model + "' returned no content for " + operation
                + " (finish reason: " + (finishReason == null ? "unknown" : finishReason)
                + "). The response contained no JSON to parse, so this is a provider behaviour "
                + "rather than a formatting problem.");
        this.model = model;
        this.finishReason = finishReason;
    }

    public String model() {
        return model;
    }

    public String finishReason() {
        return finishReason;
    }
}
