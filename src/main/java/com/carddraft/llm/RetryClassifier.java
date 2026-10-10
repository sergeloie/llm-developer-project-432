package com.carddraft.llm;

/**
 * Decides whether a failed model call is worth attempting again.
 *
 * <p>The principle is one sentence: retry what can change on its own, and do not retry what a
 * retry cannot fix. A rate limit will clear, a server error will pass, a network blip will
 * resolve. A malformed request stays malformed, an expired key stays expired, a model that does
 * not exist does not start existing — and retrying those only buys the same answer later, at
 * cost.
 *
 * <p>Status handling is expressed once, as a rule about the code, rather than as a list of the
 * SDK's per-status exception classes. Those classes are Kotlin types with synthetic constructors
 * and one class per status; naming them all would be a longer list that drifts when the SDK adds
 * a status, and it would put the actual policy somewhere it cannot be read at a glance.
 */
final class RetryClassifier {

    private RetryClassifier() {}

    static boolean isRetryable(Throwable error) {
        return switch (error) {
            case com.openai.errors.OpenAIIoException ignored -> true;
            case com.openai.errors.OpenAIRetryableException ignored -> true;
            case java.net.SocketTimeoutException ignored -> true;
            case java.io.IOException ignored -> true;
            case com.openai.errors.OpenAIServiceException service -> isTransientStatus(service.statusCode());
            default -> false;
        };
    }

    /**
     * A rate limit is a request to slow down; a 5xx is a server that is briefly unwell. Neither
     * changes by being asked again more insistently. Everything else in the 4xx range describes a
     * request that will be rejected identically however many times it is sent.
     */
    static boolean isTransientStatus(int statusCode) {
        return statusCode == 429 || statusCode >= 500;
    }
}
