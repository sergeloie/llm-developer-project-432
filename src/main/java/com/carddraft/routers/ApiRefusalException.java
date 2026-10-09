package com.carddraft.routers;

import org.springframework.http.HttpStatus;

/**
 * An API refusal that carries its own documented status.
 *
 * <p>Raised by controllers instead of returning a hand-rolled {@code Map.of("error", ...)} body;
 * the shared advice turns it into an RFC-7807 {@code ProblemDetail}. The status travels with the
 * exception because the two refusals that are not plain 400s — a conflict and a payload over the
 * limit — carry no other meaning without it.
 */
public class ApiRefusalException extends RuntimeException {

    private final HttpStatus status;
    private final java.util.Map<String, Object> properties;

    public ApiRefusalException(HttpStatus status, String detail) {
        this(status, detail, java.util.Map.of());
    }

    public ApiRefusalException(HttpStatus status, String detail, java.util.Map<String, Object> properties) {
        super(detail);
        this.status = status;
        this.properties = properties;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public java.util.Map<String, Object> getProperties() {
        return properties;
    }
}