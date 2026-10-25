package com.swiftdrop.apigateway.filter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Gateway filter enforcing bounded concurrent connection quotas and connection abuse policies
 * on Server-Sent Events (SSE) tracking and lifecycle streams.
 *
 * Enforces per-client concurrency caps:
 *   - Public tracking streams (/api/v1/tracking/{trackingNumber}/events): bounded by client IP (default: 5)
 *   - Authenticated streams (/api/v1/parcels/{id}/events): bounded by authenticated subject (default: 10)
 *
 * Guarantees zero response body buffering in the Gateway reactive pipeline.
 * Cleanly decrements connection counters upon client disconnect, cancel, or completion.
 */
@Component
public class SseConnectionPolicyGatewayFilter implements GlobalFilter, Ordered {

    private final boolean enabled;
    private final int maxPublicConnections;
    private final int maxAuthenticatedConnections;

    private final Map<String, AtomicInteger> activeConnections = new ConcurrentHashMap<>();

    public SseConnectionPolicyGatewayFilter(
            @Value("${swiftdrop.gateway.sse.enabled:true}") boolean enabled,
            @Value("${swiftdrop.gateway.sse.max-public-connections:5}") int maxPublicConnections,
            @Value("${swiftdrop.gateway.sse.max-authenticated-connections:10}") int maxAuthenticatedConnections) {
        this.enabled = enabled;
        this.maxPublicConnections = maxPublicConnections;
        this.maxAuthenticatedConnections = maxAuthenticatedConnections;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!enabled) {
            return chain.filter(exchange);
        }

        String path = exchange.getRequest().getPath().value();
        String acceptHeader = exchange.getRequest().getHeaders().getFirst("Accept");
        boolean isSse = path.endsWith("/events") || (acceptHeader != null && acceptHeader.contains(MediaType.TEXT_EVENT_STREAM_VALUE));

        if (!isSse) {
            return chain.filter(exchange);
        }

        boolean isPublicStream = path.startsWith("/api/v1/tracking/");

        if (isPublicStream) {
            String clientIp = resolveClientIp(exchange);
            String key = "sse:pub:" + clientIp;
            return enforceConcurrency(exchange, chain, path, key, maxPublicConnections);
        }

        // Authenticated stream: resolve subject from reactive security context
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .map(java.util.Optional::of)
                .defaultIfEmpty(java.util.Optional.empty())
                .flatMap(optAuth -> {
                    org.springframework.security.core.Authentication auth = optAuth.orElse(null);
                    String principalName = (auth != null && auth.getName() != null) ? auth.getName() : resolveClientIp(exchange);
                    String key = "sse:auth:" + principalName;
                    return enforceConcurrency(exchange, chain, path, key, maxAuthenticatedConnections);
                });
    }

    private Mono<Void> enforceConcurrency(
            ServerWebExchange exchange,
            GatewayFilterChain chain,
            String path,
            String key,
            int maxAllowed) {
        AtomicInteger counter = activeConnections.computeIfAbsent(key, k -> new AtomicInteger(0));
        int current = counter.incrementAndGet();

        if (current > maxAllowed) {
            counter.decrementAndGet();
            return reject(exchange, path, maxAllowed);
        }

        // Forward without buffering, decrementing counter when connection closes/terminates
        return chain.filter(exchange)
                .doFinally(signalType -> counter.decrementAndGet());
    }

    private Mono<Void> reject(ServerWebExchange exchange, String path, int maxAllowed) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        response.getHeaders().setContentType(MediaType.valueOf("application/problem+json"));

        String json = "{\"type\":\"about:blank\"," +
                "\"title\":\"Too Many Requests\"," +
                "\"status\":429," +
                "\"detail\":\"Concurrent SSE connection limit exceeded. Maximum allowed: " + maxAllowed + ".\"," +
                "\"instance\":\"" + escapeJson(path) + "\"}";

        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }

    private String resolveClientIp(ServerWebExchange exchange) {
        InetSocketAddress remoteAddress = exchange.getRequest().getRemoteAddress();
        if (remoteAddress != null && remoteAddress.getAddress() != null) {
            return remoteAddress.getAddress().getHostAddress();
        }
        return "127.0.0.1";
    }

    private static String escapeJson(String input) {
        if (input == null) {
            return "";
        }
        return input.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public int getActiveConnectionsCount(String key) {
        AtomicInteger counter = activeConnections.get(key);
        return counter != null ? counter.get() : 0;
    }

    public void clear() {
        activeConnections.clear();
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 30;
    }
}
