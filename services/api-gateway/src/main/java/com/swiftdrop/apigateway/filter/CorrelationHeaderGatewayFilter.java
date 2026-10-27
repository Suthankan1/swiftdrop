package com.swiftdrop.apigateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Spring Cloud Gateway GlobalFilter responsible for establishing safe distributed correlation context.
 *
 * Runs immediately following StripUntrustedHeadersGatewayFilter (HIGHEST_PRECEDENCE + 1).
 *
 * Policy:
 * 1. Checks untrusted caller-supplied request tracing headers (X-Correlation-ID, X-Request-ID).
 * 2. Validates against the bounded safe-character policy (^[a-zA-Z0-9_-]{1,64}$).
 * 3. Rejects CRLF injection, control characters, script tags, and oversized strings.
 * 4. Normalizes valid identifiers or generates a cryptographically random UUID when missing/invalid.
 * 5. Injects the trusted canonical header 'X-SwiftDrop-Correlation-ID' into the downstream request.
 * 6. Attaches 'X-SwiftDrop-Correlation-ID' to the HTTP response header for client-side observability.
 */
@Component
public class CorrelationHeaderGatewayFilter implements GlobalFilter, Ordered {

    public static final String CORRELATION_ID_HEADER = "X-Correlation-ID";
    private static final List<String> INBOUND_CANDIDATE_HEADERS = List.of(
            "X-Correlation-ID",
            "X-Request-ID"
    );
    private static final Pattern SAFE_CORRELATION_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String incomingId = extractCandidateCorrelationId(exchange.getRequest());
        String safeCorrelationId = normalizeOrGenerate(incomingId);

        // Mutate downstream request with safe, normalized X-Correlation-ID
        ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                .header(CORRELATION_ID_HEADER, safeCorrelationId)
                .build();

        // Echo safe correlation ID in the HTTP response
        exchange.getResponse().getHeaders().set(CORRELATION_ID_HEADER, safeCorrelationId);

        return chain.filter(exchange.mutate().request(mutatedRequest).build());
    }

    private String extractCandidateCorrelationId(ServerHttpRequest request) {
        for (String headerName : INBOUND_CANDIDATE_HEADERS) {
            String value = request.getHeaders().getFirst(headerName);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    public static String normalizeOrGenerate(String raw) {
        if (raw == null) {
            return UUID.randomUUID().toString();
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || trimmed.length() > 64) {
            return UUID.randomUUID().toString();
        }
        if (SAFE_CORRELATION_ID_PATTERN.matcher(trimmed).matches()) {
            return trimmed;
        }
        return UUID.randomUUID().toString();
    }

    @Override
    public int getOrder() {
        // Runs right after StripUntrustedHeadersGatewayFilter (HIGHEST_PRECEDENCE)
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }
}
