package com.carddraft.routers;

import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The single place that turns a refused request into an RFC-7807 {@code ProblemDetail}.
 *
 * <p>Hand-rolled {@code Map.of("error", ...)} bodies in the controllers died for this: one mapping
 * per status, and every refusal carrying the same shape — status, type, title, detail — so a
 * caller can rely on the contract instead of on what each endpoint happened to write.
 *
 * <p>An {@link IllegalArgumentException} is a client-side refusal and maps to 400. An unknown job
 * status is also a refusal, never a 500: the controller parses statuses leniently, and anything
 * that slips past that parsing in enum form still lands here as a 400 rather than blowing up in
 * the error plumbing.
 */
@RestControllerAdvice
public class ApiErrorAdvice {

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail onClientRefusal(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    /**
     * A request body that failed its constraints.
     *
     * <p>Each offending field is named alongside the reason, because "the body was invalid" tells a
     * caller to re-read the whole contract while "supplierText: must not be blank" tells it what to
     * change.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail onInvalidBody(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
    }

    @ExceptionHandler(WorkflowNotFoundException.class)
    public ProblemDetail onWorkflowNotFound(WorkflowNotFoundException e) {
        return ProblemDetail.forStatusAndDetail(e.getStatus(), e.getMessage());
    }

    @ExceptionHandler(ApiRefusalException.class)
    public ProblemDetail onRefusal(ApiRefusalException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(e.getStatus(), e.getMessage());
        e.getProperties().forEach(problem::setProperty);
        return problem;
    }
}
