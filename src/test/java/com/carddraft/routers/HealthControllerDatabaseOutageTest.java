package com.carddraft.routers;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * The reason there are two health endpoints rather than one.
 *
 * <p>The datasource points at a closed port and the pool is told not to verify itself at
 * startup, so the application comes up with the database unreachable — which is the situation
 * an orchestrator has to distinguish: this process is fine, its dependency is not.
 *
 * <p>A single combined endpoint would fail here, and the failure would be indistinguishable
 * from a crash. That matters operationally: restarting every instance during a database outage
 * does not repair the database, and it destroys in-flight work each time.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.flyway.enabled=false",
                "card.db.url=jdbc:postgresql://127.0.0.1:1/card",
                "card.db.pool-size=2",
                "card.db.connection-timeout=1s",
                "card.db.initialization-fail-timeout-millis=-1"
        })
@AutoConfigureTestRestTemplate
class HealthControllerDatabaseOutageTest {

    @Autowired
    TestRestTemplate rest;

    @Test
    void livenessStaysUpAndReadinessFailsWhenTheDatabaseIsUnreachable() {
        ResponseEntity<Map> liveness = rest.getForEntity("/health/live", Map.class);
        ResponseEntity<Map> readiness = rest.getForEntity("/health/ready", Map.class);

        assertThat(liveness.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(liveness.getBody()).containsEntry("status", "UP");

        assertThat(readiness.getStatusCode())
                .as("readiness report was %s", readiness.getBody())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(readiness.getBody()).containsEntry("status", "DOWN");
    }
}
