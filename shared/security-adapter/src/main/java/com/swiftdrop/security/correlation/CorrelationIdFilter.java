package com.swiftdrop.security.correlation;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Servlet filter that extracts, validates, and propagates distributed correlation identifiers
 * across HTTP boundaries in Spring MVC applications.
 *
 * Inspects incoming correlation headers in order:
 * 1. X-SwiftDrop-Correlation-ID (internal forwarded header)
 * 2. X-Correlation-ID (standard public request header)
 * 3. X-Request-ID (alternate request tracing header)
 *
 * Untrusted, malformed, excessive (>64 chars), or CRLF-injected values are safely normalized
 * or replaced with fresh UUIDs via CorrelationContext.
 *
 * Sets the resolved correlation identifier on the HTTP response header and strictly cleanses
 * ThreadLocal and MDC state in a finally block to eliminate thread pool leakage.
 */
public class CorrelationIdFilter extends OncePerRequestFilter implements Ordered {

    private static final List<String> CORRELATION_HEADERS = List.of(
            CorrelationContext.CORRELATION_ID_HEADER,
            "X-Correlation-ID",
            "X-Request-ID"
    );

    private final int order;

    public CorrelationIdFilter() {
        this(Ordered.HIGHEST_PRECEDENCE + 50);
    }

    public CorrelationIdFilter(int order) {
        this.order = order;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        String incomingHeader = resolveCorrelationHeader(request);
        String safeCorrelationId = CorrelationContext.set(incomingHeader);

        // Echo the safe correlation ID in the response
        response.setHeader(CorrelationContext.CORRELATION_ID_HEADER, safeCorrelationId);
        response.setHeader("X-Correlation-ID", safeCorrelationId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            CorrelationContext.clear();
        }
    }

    private String resolveCorrelationHeader(HttpServletRequest request) {
        for (String headerName : CORRELATION_HEADERS) {
            String value = request.getHeader(headerName);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    @Override
    public int getOrder() {
        return order;
    }
}
