package com.carddraft.llm;

import java.math.BigDecimal;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class LlmSettingsTest {

    private static final Duration CEILING = Duration.ofSeconds(8);

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(TestConfiguration.class);

    @EnableConfigurationProperties(LlmSettings.class)
    static class TestConfiguration {}

    @Test
    void defaultsTheTemperatureToZero() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(LlmSettings.class).temperature()).isEqualByComparingTo(BigDecimal.ZERO);
        });
    }

    @Test
    void bindsTheTemperatureFromConfiguration() {
        runner.withPropertyValues("card.llm.temperature=0.7").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(LlmSettings.class).temperature()).isEqualByComparingTo(new BigDecimal("0.7"));
        });
    }

    @Test
    void refusesToStartOnANegativePrice() {
        runner.withPropertyValues("card.llm.main-input-price-per-million=-0.01").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasStackTraceContaining("card.llm.main-input-price-per-million")
                    .hasStackTraceContaining("LlmSettings");
        });
    }

    @Test
    void jitterNeverCarriesADelayPastItsConfiguredCeiling() {
        LlmSettings settings = new LlmSettings(
                "main-model",
                "utility-model",
                3,
                Duration.ofMillis(500),
                CEILING,
                2,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO);

        for (int attempt = 1; attempt <= 40; attempt++) {
            for (int sample = 0; sample < 200; sample++) {
                assertThat(settings.delayBefore(attempt))
                        .as("delay before attempt %d, sample %d", attempt, sample)
                        .isGreaterThanOrEqualTo(Duration.ZERO)
                        .isLessThanOrEqualTo(CEILING);
            }
        }
    }
}
