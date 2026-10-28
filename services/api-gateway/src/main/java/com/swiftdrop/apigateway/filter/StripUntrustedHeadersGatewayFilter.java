package com.swiftdrop.apigateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Strips client-supplied identity claims and internal service authentication headers
 * so downstream services cannot be tricked by spoofed or forged headers.
 *
 * In accordance with HTTP semantics, header sanitization is strictly case-insensitive
 * and cleanses all duplicate header instances.
 *
 * Authentic client credentials (e.g. Authorization: Bearer <token>) and approved merchant
 * API keys (X-API-Key) are strictly preserved for downstream verification.
 */
@Component
public class StripUntrustedHeadersGatewayFilter implements GlobalFilter, Ordered {

    private static final Set<String> FORBIDDEN_HEADERS_LOWER = Set.of(
            // User and role identity claims
            "x-user-id",
            "x-roles",
            "x-user-roles",
            "x-tenant-id",
            "x-keycloak-sub",
            "x-user-email",
            "x-user-sub",
            "x-merchant-id",
            "x-courier-id",
            "x-actor-id",
            "x-forwarded-user",
            "x-forwarded-roles",
            "x-forwarded-groups",
            "x-forwarded-email",
            "x-forwarded-sub",
            "x-forwarded-preferred-username",
            "x-gateway-auth",

            // Internal gRPC service authentication & actor delegation context (Issue #100 / PR #188)
            "x-swiftdrop-service-token",
            "x-swiftdrop-actor-token",
            "x-swiftdrop-actor-sub",
            "x-swiftdrop-actor-roles",
            "x-swiftdrop-correlation-id"
    );

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(headers -> {
                    Set<String> headerNames = new HashSet<>(headers.headerNames());
                    for (String headerName : headerNames) {
                        if (headerName != null && FORBIDDEN_HEADERS_LOWER.contains(headerName.toLowerCase(Locale.ROOT))) {
                            headers.remove(headerName);
                        }
                    }
                })
                .build();
        return chain.filter(exchange.mutate().request(request).build());
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
