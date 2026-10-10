package com.carddraft.context;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class ContextSettingsTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(TestConfiguration.class);

    @EnableConfigurationProperties(ContextSettings.class)
    static class TestConfiguration {}

    @Test
    void refusesToStartOnAContextOfNoChunks() {
        runner.withPropertyValues("card.context.max-chunks=0").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasStackTraceContaining("card.context.max-chunks")
                    .hasStackTraceContaining("ContextSettings");
        });
    }
}
