package com.carddraft.core;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class CardDatabaseSettingsTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(TestConfiguration.class);

    @EnableConfigurationProperties(CardDatabaseSettings.class)
    static class TestConfiguration {}

    @Test
    void bindsTypedSettingsFromConfiguration() {
        runner.withPropertyValues(
                        "card.db.url=jdbc:postgresql://127.0.0.1:5432/card",
                        "card.db.username=card",
                        "card.db.password=card",
                        "card.db.pool-size=7")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(CardDatabaseSettings.class).poolSize())
                            .isEqualTo(7);
                });
    }

    @Test
    void refusesToStartOnAnImpossiblePoolSize() {
        runner.withPropertyValues(
                        "card.db.url=jdbc:postgresql://127.0.0.1:5432/card",
                        "card.db.username=card",
                        "card.db.password=card",
                        "card.db.pool-size=0")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("pool-size")
                            .hasStackTraceContaining("CardDatabaseSettings");
                });
    }
}
