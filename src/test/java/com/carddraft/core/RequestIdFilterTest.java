package com.carddraft.core;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the identifier is present on log records, not merely computed.
 *
 * <p>Attaching a capturing appender is the only way to observe this honestly: asserting that a
 * filter put a value into the MDC would check the filter against itself, whereas asserting that
 * the value appears on an emitted log record checks the property that actually matters — one
 * search by this identifier finds the request.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@TestPropertySource(
        properties = {
            "spring.flyway.enabled=false",
            "card.db.url=jdbc:postgresql://127.0.0.1:1/card",
            "card.db.pool-size=2",
            "card.db.initialization-fail-timeout-millis=-1",
            "logging.level.com.carddraft.core.RequestIdFilter=DEBUG"
        })
class RequestIdFilterTest {

    @Autowired
    TestRestTemplate rest;

    private ListAppender<ILoggingEvent> captured;
    private Logger root;

    @BeforeEach
    void attachAppender() {
        captured = new ListAppender<>();
        captured.start();
        root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        root.addAppender(captured);
    }

    @AfterEach
    void detachAppender() {
        root.detachAppender(captured);
        captured.stop();
    }

    @Test
    void anIdentifierSuppliedByTheCallerReachesTheLogRecords() {
        rest.exchange(
                "/actuator/health/liveness?probe=1",
                HttpMethod.GET,
                new HttpEntity<>(headers("caller-supplied-id")),
                Map.class);

        List<ILoggingEvent> events = captured.list.stream()
                .filter(event -> event.getLoggerName().endsWith("RequestIdFilter"))
                .toList();

        assertThat(events).isNotEmpty();
        assertThat(events).allSatisfy(event -> assertThat(event.getMDCPropertyMap())
                .containsEntry(RequestIdFilter.MDC_KEY, "caller-supplied-id"));
    }

    @Test
    void anIdentifierIsGeneratedWhenTheCallerSuppliesNone() {
        ResponseEntity<Map> response = rest.getForEntity("/actuator/health/liveness?probe=2", Map.class);

        assertThat(response.getHeaders().getFirst(RequestIdFilter.HEADER)).isNotBlank();
        assertThat(captured.list.stream()
                        .filter(event -> event.getLoggerName().endsWith("RequestIdFilter"))
                        .filter(event -> event.getMDCPropertyMap().containsKey(RequestIdFilter.MDC_KEY)))
                .isNotEmpty();
    }

    @Test
    void theSuppliedIdentifierIsEchoedBackToTheCaller() {
        ResponseEntity<Map> response = rest.exchange(
                "/actuator/health/liveness?probe=3", HttpMethod.GET, new HttpEntity<>(headers("echoed-id")), Map.class);

        assertThat(response.getHeaders().getFirst(RequestIdFilter.HEADER)).isEqualTo("echoed-id");
    }

    private HttpHeaders headers(String requestId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(RequestIdFilter.HEADER, requestId);
        return headers;
    }
}
