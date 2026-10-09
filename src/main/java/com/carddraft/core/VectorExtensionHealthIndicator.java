package com.carddraft.core;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Reports whether the vector extension this service's search depends on is installed.
 *
 * <p>The extension check earns its place. {@code CREATE EXTENSION vector} activates something
 * already compiled in, so a wrong database image produces a schema that migrates cleanly and then
 * fails at the first search. As an Actuator contribution it turns that into a readiness fact
 * rather than a failure discovered by the first user.
 *
 * <p>Reads through {@code JdbcClient} like every other query in the service.
 */
@Component
public class VectorExtensionHealthIndicator implements HealthIndicator {

    private final JdbcClient jdbc;

    public VectorExtensionHealthIndicator(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Health health() {
        try {
            return jdbc.sql("SELECT extversion FROM pg_extension WHERE extname = 'vector'")
                    .query(String.class)
                    .optional()
                    .map(version -> Health.up().withDetail("version", version).build())
                    .orElseGet(() -> Health.down()
                            .withDetail("reason", "extension not installed in this database")
                            .build());
        } catch (RuntimeException e) {
            return Health.down()
                    .withDetail("reason", e.getClass().getSimpleName() + ": " + e.getMessage())
                    .build();
        }
    }
}
