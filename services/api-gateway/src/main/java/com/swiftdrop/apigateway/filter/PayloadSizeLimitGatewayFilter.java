package com.swiftdrop.apigateway.filter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * Gateway filter enforcing bounded payload size limits at the business edge.
 * Protects downstream microservices from memory exhaustion, large payload denial-of-service,
 * and oversized request attacks.
 *
 * Emits standardized RFC 7807 Problem Details with HTTP 413 Payload Too Large.
 */
@Component
public class PayloadSizeLimitGatewayFilter implements GlobalFilter, Ordered {

    private final boolean enabled;
    private final long maxQuoteBytes;
    private final long maxStandardBytes;
    private final long maxProofBytes;

    public PayloadSizeLimitGatewayFilter(
            @Value("${swiftdrop.gateway.payload.enabled:true}") boolean enabled,
            @Value("${swiftdrop.gateway.payload.max-quote-bytes:65536}") long maxQuoteBytes,
            @Value("${swiftdrop.gateway.payload.max-standard-bytes:262144}") long maxStandardBytes,
            @Value("${swiftdrop.gateway.payload.max-proof-bytes:5242880}") long maxProofBytes) {
        this.enabled = enabled;
        this.maxQuoteBytes = maxQuoteBytes;
        this.maxStandardBytes = maxStandardBytes;
        this.maxProofBytes = maxProofBytes;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!enabled) {
            return chain.filter(exchange);
        }

        HttpMethod method = exchange.getRequest().getMethod();
        if (method != HttpMethod.POST && method != HttpMethod.PUT && method != HttpMethod.PATCH) {
            return chain.filter(exchange);
        }

        long contentLength = exchange.getRequest().getHeaders().getContentLength();
        if (contentLength <= 0) {
            return chain.filter(exchange);
        }

        String path = exchange.getRequest().getPath().value();
        long allowedLimit = resolveMaxLimit(path);

        if (contentLength > allowedLimit) {
            return reject(exchange, path, contentLength, allowedLimit);
        }

        return chain.filter(exchange);
    }

    private long resolveMaxLimit(String path) {
        if (path.startsWith("/api/v1/quotes")) {
            return maxQuoteBytes;
        }
        if (path.contains("/proof")) {
            return maxProofBytes;
        }
        return maxStandardBytes;
    }

    private Mono<Void> reject(ServerWebExchange exchange, String path, long actual, long maxAllowed) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.PAYLOAD_TOO_LARGE);
        response.getHeaders().setContentType(MediaType.valueOf("application/problem+json"));

        String json = "{\"type\":\"about:blank\"," +
                "\"title\":\"Payload Too Large\"," +
                "\"status\":413," +
                "\"detail\":\"Request payload (" + actual + " bytes) exceeds maximum limit of " + maxAllowed + " bytes.\"," +
                "\"instance\":\"" + escapeJson(path) + "\"}";

        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }

    private static String escapeJson(String input) {
        if (input == null) {
            return "";
        }
        return input.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}
