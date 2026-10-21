package com.swiftdrop.apigateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Strips client-supplied identity claims so downstream services cannot be tricked by spoofed headers.
 * The original Authorization bearer token is strictly preserved for downstream verification.
 */
@Component
public class StripUntrustedHeadersGatewayFilter implements GlobalFilter, Ordered {

    private static final List<String> FORBIDDEN_HEADERS = List.of(
            "X-User-Id", "x-user-id",
            "X-Roles", "x-roles",
            "X-Tenant-Id", "x-tenant-id",
            "X-Keycloak-Sub", "x-keycloak-sub",
            "X-User-Email", "x-user-email"
    );

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(headers -> {
                    for (String header : FORBIDDEN_HEADERS) {
                        headers.remove(header);
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
