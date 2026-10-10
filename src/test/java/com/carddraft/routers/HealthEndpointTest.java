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
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = "test.context-id=health")
class HealthEndpointTest {

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
    void livenessAnswersSuccessWhileTheServiceIsRunning() {
        ResponseEntity<Map> response = rest.getForEntity("/actuator/health/liveness", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "UP");
    }

    @Test
    @SuppressWarnings("unchecked")
    void readinessReportsTheDatabaseAndTheVectorExtension() {
        ResponseEntity<Map> response = rest.getForEntity("/actuator/health/readiness", Map.class);

        assertThat(response.getStatusCode())
                .as("readiness report was %s", response.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "UP");

        Map<String, Object> components =
                (Map<String, Object>) response.getBody().get("components");
        assertThat(components).containsKeys("db", "vectorExtension");
        assertThat((Map<String, Object>) components.get("db")).containsEntry("status", "UP");

        Map<String, Object> vectorExtension = (Map<String, Object>) components.get("vectorExtension");
        assertThat(vectorExtension).containsEntry("status", "UP");
        assertThat((Map<String, Object>) vectorExtension.get("details")).containsKey("version");
    }
}
