package com.carddraft.temporal;

import java.time.Duration;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;

/**
 * The options a step stub is built from, declared once for both workflows.
 *
 * <p>Module constants rather than configuration, because workflow code may not read configuration:
 * a value that can change between replays sends the same history down a different path (ADR-0004).
 *
 * <p>A generation step waits on the model for tens of seconds and can legitimately take minutes, so
 * five minutes is a ceiling rather than a target. Three attempts, because the failure these steps
 * are most exposed to is a resource that is legitimately not up yet, such as the model server
 * during a fresh start.
 */
final class StepActivityOptions {

    static final Duration TIMEOUT = Duration.ofMinutes(5);

    static final RetryOptions RETRY = RetryOptions.newBuilder()
            .setMaximumAttempts(3)
            .setInitialInterval(Duration.ofSeconds(1))
            .build();

    private StepActivityOptions() {}

    static ActivityOptions options() {
        return ActivityOptions.newBuilder()
                .setStartToCloseTimeout(TIMEOUT)
                .setRetryOptions(RETRY)
                .build();
    }
}
