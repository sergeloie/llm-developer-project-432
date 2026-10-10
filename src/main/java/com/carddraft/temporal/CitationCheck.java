package com.carddraft.temporal;

import java.util.List;

/**
 * What citation verification found, as the workflow needs it.
 *
 * <p>A typed value rather than a JSON string for the same reason {@link ReviewOutcome} is one:
 * workflow code is replayed from history, and every line of it must be a pure function of what was
 * recorded. Parsing is pure, but it is also code that can change under a history that has already
 * been written, which is exactly the fragility a type removes.
 *
 * @param clean        true when every citation names a fragment from the submitted context
 * @param messages     what to tell the model on rework, naming each offending claim
 * @param fabricated   count of citations naming a fragment the model was never shown, for the log
 * @param citationRetries how many times this job has already been sent back for citation repair
 */
public record CitationCheck(boolean clean, List<String> messages, int fabricated, int citationRetries) {

    public CitationCheck {
        messages = messages == null ? List.of() : List.copyOf(messages);
    }

    public CitationCheck withoutRetries() {
        return new CitationCheck(clean, messages, fabricated, 0);
    }

    public CitationCheck afterRework() {
        return new CitationCheck(clean, messages, fabricated, citationRetries + 1);
    }
}
