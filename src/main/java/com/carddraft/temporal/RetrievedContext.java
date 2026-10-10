package com.carddraft.temporal;

import java.util.List;

/**
 * What retrieval assembled, and what the trust layer did to it.
 *
 * <p>One value rather than a rendered string, because screening can end the job before any
 * generation happens: a document whose fragments are mostly injected instructions goes to a
 * person with a reason, and the workflow can only make that decision if the reason crosses
 * the step boundary with the context. A bare string would leave the escalation behind.
 */
public record RetrievedContext(
        String contextText, List<String> excluded, List<String> masked, boolean escalated, String escalationReason) {

    public RetrievedContext {
        excluded = excluded == null ? List.of() : List.copyOf(excluded);
        masked = masked == null ? List.of() : List.copyOf(masked);
    }

    /** No exclusions, no escalation: the common case. */
    public static RetrievedContext clean(String contextText) {
        return new RetrievedContext(contextText, List.of(), List.of(), false, null);
    }
}
