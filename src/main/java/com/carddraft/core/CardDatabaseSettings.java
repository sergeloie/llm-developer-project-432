package com.carddraft.core;

import java.time.Duration;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Database settings, bound once at startup.
 *
 * <p>Types here are not decoration: a non-numeric pool size has to fail the context with a
 * legible message, not surface forty minutes into a background job after a model has already
 * been paid for. Defaults exist so the service starts on a fresh clone with no configuration
 * at all.
 */
@Validated
@ConfigurationProperties("card.db")
public record CardDatabaseSettings(

        @DefaultValue("jdbc:postgresql://127.0.0.1:5432/card") @NotBlank String url,
        @DefaultValue("card") @NotBlank String username,
        @DefaultValue("card") @NotBlank String password,
        @DefaultValue("10") @Min(1) int poolSize,

        /**
         * How long a caller waits for a free connection. Kept short on purpose: a generation
         * step must not sit on a pool slot waiting minutes for the model, so queueing for a
         * connection has to fail fast and visibly rather than wait quietly.
         */
        @DefaultValue("5s") @NotNull Duration connectionTimeout,

        /**
         * How long the pool may spend verifying itself at startup before giving up.
         *
         * <p>The default is one millisecond, which means the service refuses to start while
         * the database is unreachable and lets the orchestrator decide what to do about it —
         * starting anyway would only produce an instance that fails every request. Tests that
         * need the application to come up <em>without</em> a database set this to a negative
         * value, which tells the pool not to verify at all.
         */
        @DefaultValue("1") long initializationFailTimeoutMillis) {
}
