package com.carddraft.routers;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers(disabledWithoutDocker = true)

@TestPropertySource(properties = "test.context-id=health")
class HealthControllerTest {

    @Container
    static final PostgreSQLContainer DATABASE = new PostgreSQLContainer("pgvector/pgvector:pg17")
            .withDatabaseName("card")
            .withUsername("card")
            .withPassword("card");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("card.db.url", DATABASE::getJdbcUrl);
        registry.add("card.db.username", DATABASE::getUsername);
        registry.add("card.db.password", DATABASE::getPassword);
    }

    @Autowired
    TestRestTemplate rest;

    @Test
    void livenessAnswersSuccessWhileTheServiceIsRunning() {
        ResponseEntity<Map> response = rest.getForEntity("/health/live", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "UP");
    }

    @Test
    @SuppressWarnings("unchecked")
    void readinessReportsThatTheVectorExtensionIsActive() {
        ResponseEntity<Map> response = rest.getForEntity("/health/ready", Map.class);

        assertThat(response.getStatusCode())
                .as("readiness report was %s", response.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "UP");

        Map<String, Object> checks = (Map<String, Object>) response.getBody().get("checks");
        assertThat(checks).containsKeys("database", "vectorExtension");
        assertThat((Map<String, Object>) checks.get("vectorExtension"))
                .containsEntry("status", "UP")
                .containsKey("version");
    }
}
