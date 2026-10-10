package com.carddraft.routers;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the vector-extension indicator is a member of the readiness group, not a bystander.
 *
 * <p>The database is reachable but Flyway never runs, so {@code CREATE EXTENSION vector} was never
 * applied and the extension is absent. The {@code db} component is therefore UP while the readiness
 * group must still be DOWN, which is only true if the custom indicator participates in the group.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"test.context-id=health-missing-vector", "spring.flyway.enabled=false"})
@AutoConfigureTestRestTemplate
@Testcontainers(disabledWithoutDocker = true)
class HealthEndpointMissingVectorExtensionTest {

    @Container
    static final PostgreSQLContainer DATABASE = new PostgreSQLContainer("pgvector/pgvector:pg17")
            .withDatabaseName("card")
            .withUsername("card")
            .withPassword("card");

    @Autowired
    TestRestTemplate rest;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("card.db.url", DATABASE::getJdbcUrl);
        registry.add("card.db.username", DATABASE::getUsername);
        registry.add("card.db.password", DATABASE::getPassword);
    }

    @Test
    @SuppressWarnings("unchecked")
    void readinessFailsWhenTheVectorExtensionIsMissing() {
        ResponseEntity<Map> response = rest.getForEntity("/actuator/health/readiness", Map.class);

        assertThat(response.getStatusCode())
                .as("readiness report was %s", response.getBody())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).containsEntry("status", "DOWN");

        Map<String, Object> components =
                (Map<String, Object>) response.getBody().get("components");
        assertThat((Map<String, Object>) components.get("db")).containsEntry("status", "UP");
        assertThat((Map<String, Object>) components.get("vectorExtension")).containsEntry("status", "DOWN");
    }
}
