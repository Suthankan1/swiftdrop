package com.swiftdrop.apigateway.filter;

import com.swiftdrop.apigateway.filter.ratelimit.InMemoryRateLimiter;
import com.swiftdrop.apigateway.security.merchant.MerchantApiKeyAuthenticationToken;
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

/**
 * Global Gateway filter enforcing bounded rate-limiting and abuse policies at the business edge.
 *
 * Route limits:
 *   - Public endpoints (tracking, quotes): bounded by client IP
 *   - Merchant machine order API: bounded by verified merchant credential identity
 *   - Unauthenticated merchant requests: strictly throttled to prevent brute-force probing
 *
 * Responses follow RFC 7807 Problem Details with HTTP 429 and Retry-After header.
 */
@Component
public class RateLimitingGatewayFilter implements GlobalFilter, Ordered {

    private final InMemoryRateLimiter rateLimiter;
    private final boolean enabled;
    private final long publicLimitPerMinute;
    private final long publicBurstCapacity;
    private final long merchantLimitPerMinute;
    private final long merchantBurstCapacity;
    private final long unauthenticatedLimitPerMinute;
    private final long unauthenticatedBurstCapacity;

    public RateLimitingGatewayFilter(
            InMemoryRateLimiter rateLimiter,
            @Value("${swiftdrop.gateway.rate-limit.enabled:true}") boolean enabled,
            @Value("${swiftdrop.gateway.rate-limit.public-limit-per-minute:60}") long publicLimitPerMinute,
            @Value("${swiftdrop.gateway.rate-limit.public-burst-capacity:10}") long publicBurstCapacity,
            @Value("${swiftdrop.gateway.rate-limit.merchant-limit-per-minute:120}") long merchantLimitPerMinute,
            @Value("${swiftdrop.gateway.rate-limit.merchant-burst-capacity:20}") long merchantBurstCapacity,
            @Value("${swiftdrop.gateway.rate-limit.unauthenticated-limit-per-minute:10}") long unauthenticatedLimitPerMinute,
            @Value("${swiftdrop.gateway.rate-limit.unauthenticated-burst-capacity:5}") long unauthenticatedBurstCapacity) {
        this.rateLimiter = rateLimiter;
        this.enabled = enabled;
        this.publicLimitPerMinute = publicLimitPerMinute;
        this.publicBurstCapacity = publicBurstCapacity;
        this.merchantLimitPerMinute = merchantLimitPerMinute;
        this.merchantBurstCapacity = merchantBurstCapacity;
        this.unauthenticatedLimitPerMinute = unauthenticatedLimitPerMinute;
        this.unauthenticatedBurstCapacity = unauthenticatedBurstCapacity;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!enabled) {
            return chain.filter(exchange);
        }

        String path = exchange.getRequest().getPath().value();

        // 1. Merchant machine endpoints
        if (path.startsWith("/api/v1/merchant/parcels")) {
            return ReactiveSecurityContextHolder.getContext()
                    .map(SecurityContext::getAuthentication)
                    .map(java.util.Optional::of)
                    .defaultIfEmpty(java.util.Optional.empty())
                    .flatMap(optAuth -> {
                        org.springframework.security.core.Authentication auth = optAuth.orElse(null);
                        String key;
                        long limit;
                        long capacity;

                        if (auth instanceof MerchantApiKeyAuthenticationToken merchantToken) {
                            // Verified merchant identity quota
                            key = "merch:" + merchantToken.getMerchantPrincipal().keyPrefix();
                            limit = merchantLimitPerMinute;
                            capacity = merchantBurstCapacity;
                        } else {
                            // Unauthenticated probe or bearer attempt quota
                            key = "unauth:" + resolveClientIp(exchange);
                            limit = unauthenticatedLimitPerMinute;
                            capacity = unauthenticatedBurstCapacity;
                        }

                        InMemoryRateLimiter.ConsumptionResult result = rateLimiter.tryConsume(key, 1, capacity, limit);
                        if (!result.allowed()) {
                            return reject(exchange, path, result.retryAfterSeconds());
                        }
                        return chain.filter(exchange);
                    });
        }

        // 2. Public endpoints (tracking, quotes)
        if (path.startsWith("/api/v1/tracking") || path.startsWith("/api/v1/quotes")) {
            String clientIp = resolveClientIp(exchange);
            String key = "pub:" + clientIp;
            InMemoryRateLimiter.ConsumptionResult result = rateLimiter.tryConsume(key, 1, publicBurstCapacity, publicLimitPerMinute);
            if (!result.allowed()) {
                return reject(exchange, path, result.retryAfterSeconds());
            }
        }

        return chain.filter(exchange);
    }

    private Mono<Void> reject(ServerWebExchange exchange, String path, long retryAfterSeconds) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        response.getHeaders().setContentType(MediaType.valueOf("application/problem+json"));
        response.getHeaders().set("Retry-After", String.valueOf(retryAfterSeconds));

        String json = "{\"type\":\"about:blank\"," +
                "\"title\":\"Too Many Requests\"," +
                "\"status\":429," +
                "\"detail\":\"Rate limit exceeded. Try again in " + retryAfterSeconds + " seconds.\"," +
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

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 20;
    }
}
