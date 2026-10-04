package com.carddraft.core;

import java.io.IOException;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Puts a request identifier into the logging context for the whole request.
 *
 * <p>Binding it once here rather than passing it to every logger is the point: a field attached
 * at the edge cannot be forgotten halfway down, whereas a hand-passed identifier can be. The
 * identifier travels in the request and is echoed back, and the job identifier later rides the
 * same context inside the worker, so one search on a job identifier reconstructs its whole
 * path across processes.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";

    private static final Logger log = LoggerFactory.getLogger(RequestIdFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = request.getHeader(HEADER);
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }
        MDC.put(MDC_KEY, requestId);
        response.setHeader(HEADER, requestId);
        log.debug("request {} {} started", request.getMethod(), request.getRequestURI());
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
