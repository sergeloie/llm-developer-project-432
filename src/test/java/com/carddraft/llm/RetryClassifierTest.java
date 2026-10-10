package com.carddraft.llm;

import java.net.SocketTimeoutException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.openai.errors.OpenAIIoException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What is worth attempting again, and what is not.
 *
 * <p>The asymmetry is the whole point. Retrying a rate limit clears; retrying a malformed request
 * buys the identical error later and at full price. A suite that only checked the retryable cases
 * would pass an implementation that retried everything — which is why the "do not retry" cases
 * are the ones worth writing carefully.
 */
class RetryClassifierTest {

    @ParameterizedTest
    @ValueSource(ints = {429, 500, 502, 503, 504})
    void treatsRateLimitsAndServerErrorsAsWorthRetrying(int statusCode) {
        assertThat(RetryClassifier.isTransientStatus(statusCode)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404, 405, 409, 413, 422})
    void treatsEveryOtherClientErrorAsNotWorthRetrying(int statusCode) {
        assertThat(RetryClassifier.isTransientStatus(statusCode)).isFalse();
    }

    @Test
    void retriesTransportFailures() {
        assertThat(RetryClassifier.isRetryable(new OpenAIIoException("connection reset")))
                .isTrue();
        assertThat(RetryClassifier.isRetryable(new SocketTimeoutException("read timed out")))
                .isTrue();
        assertThat(RetryClassifier.isRetryable(new java.io.IOException("broken pipe")))
                .isTrue();
    }

    @Test
    void doesNotRetryFailuresThatAnotherAttemptCannotFix() {
        assertThat(RetryClassifier.isRetryable(new IllegalStateException("a bug in our code")))
                .isFalse();
        assertThat(RetryClassifier.isRetryable(new ModelResponseFormatException("no JSON in the response")))
                .as("the same prompt will produce the same unparseable response")
                .isFalse();
        assertThat(RetryClassifier.isRetryable(new EmptyModelResponseException("m", "stop", "extractFacts")))
                .as("a model that returned nothing will return nothing again")
                .isFalse();
    }
}
