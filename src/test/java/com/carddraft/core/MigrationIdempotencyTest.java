package com.carddraft.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The schema has to converge from nothing and then stay put.
 *
 * <p>The failure this guards against is specific: a migration that is not idempotent applies on
 * every start, and the second start fails — on a developer's machine that already has the
 * schema, which is precisely the environment where it looks fine.
 */
@Testcontainers(disabledWithoutDocker = true)
class MigrationIdempotencyTest {

    @Container
    static final PostgreSQLContainer DATABASE = new PostgreSQLContainer("pgvector/pgvector:pg17")
            .withDatabaseName("card")
            .withUsername("card")
            .withPassword("card");

    @Test
    void firstRunAppliesTheMigrationAndSecondRunAppliesNothingNew() {
        Flyway flyway = Flyway.configure()
                .dataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword())
                .locations("classpath:db/migration")
                .load();

        MigrateResult first = flyway.migrate();
        MigrateResult second = flyway.migrate();

        assertThat(first.migrationsExecuted)
                .as("the first run must apply the vector-extension migration")
                .isEqualTo(1);
        assertThat(second.migrationsExecuted)
                .as("a second run must find nothing new to apply")
                .isZero();
        assertThat(second.success).isTrue();
    }
}
