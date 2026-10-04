package com.carddraft.temporal;

import java.time.Duration;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Process engine settings.
 *
 * <p>None of these are read inside workflow code — the retry budget travels in the request and the
 * timeouts are constants — so changing them here cannot make a replayed history diverge. They
 * configure the client and the worker only.
 */
@Validated
@ConfigurationProperties("card.temporal")
public record TemporalSettings(

        /** {@code local} runs an in-process server; otherwise a host:port. */
        @DefaultValue("local") @NotBlank String target,

        @DefaultValue("default") @NotBlank String namespace,

        @DefaultValue("card-drafting") @NotBlank String taskQueue,

        /** Longest a caller will wait for a result before being told to come back later. */
        @DefaultValue("PT0.5S") Duration resultTimeout,

        @DefaultValue("100") @Min(1) int maxWorkflowThreads,
        @DefaultValue("200") @Min(1) int maxActivityThreads) {

    public boolean isLocal() {
        return "local".equalsIgnoreCase(target);
    }
}
