package com.carddraft.temporal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

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

        /**
         * The process engine's address.
         *
         * <p>An address, and only ever an address. This used to accept the word {@code local} as a
         * second mode, on the understanding that it would run the engine inside this process. It does
         * not: the SDK's "local" stubs connect to 127.0.0.1:7233 and expect something to be listening
         * there. So the setting had two spellings and one behaviour, which is worse than either — an
         * operator who set it to {@code local} had removed no dependency at all while believing they
         * had. The default is where the compose stack publishes the engine, which is the address a
         * developer running {@code docker compose up} already has.
         */
        @DefaultValue("127.0.0.1:7233") @NotBlank String target,

        @DefaultValue("default") @NotBlank String namespace,

        @DefaultValue("card-drafting") @NotBlank String taskQueue,

        @DefaultValue("100") @Min(1) int maxWorkflowThreads,
        @DefaultValue("200") @Min(1) int maxActivityThreads) {}
