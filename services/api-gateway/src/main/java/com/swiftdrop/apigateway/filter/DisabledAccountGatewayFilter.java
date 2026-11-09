package com.swiftdrop.apigateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rejects requests from users an admin has disabled, even while their access token is still valid.
 * Asks Accounts ({@code GET /api/v1/users/me/access}) with the caller's own token and caches the answer per subject
 * for a short time, so a disable takes effect within the cache window instead of the token lifetime.
 * If Accounts cannot be reached the request is allowed (availability over a short enforcement gap), and the
 * failure is cached briefly so an outage does not add latency to every request.
 */
@Component
public class DisabledAccountGatewayFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(DisabledAccountGatewayFilter.class);
    private static final int MAX_CACHE_ENTRIES = 10_000;
    private static final Duration FAILURE_TTL = Duration.ofSeconds(5);

    record CachedStatus(boolean disabled, long expiresAtMillis) { }

    private final boolean enabled;
    private final Duration ttl;
    private final WebClient accounts;
    private final Map<String, CachedStatus> cache = new ConcurrentHashMap<>();

    public DisabledAccountGatewayFilter(
            @Value("${swiftdrop.gateway.disabled-account-check.enabled:true}") boolean enabled,
            @Value("${swiftdrop.gateway.disabled-account-check.ttl-seconds:20}") long ttlSeconds,
            @Value("${ACCOUNTS_SERVICE_URL:http://localhost:8081}") String accountsUrl) {
        this.enabled = enabled;
        this.ttl = Duration.ofSeconds(Math.max(1, Math.min(ttlSeconds, 300)));
        this.accounts = WebClient.builder().baseUrl(accountsUrl).build();
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!enabled) {
            return chain.filter(exchange);
        }
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .filter(JwtAuthenticationToken.class::isInstance)
                .cast(JwtAuthenticationToken.class)
                .flatMap(auth -> isDisabled(auth.getToken().getSubject(), auth.getToken().getTokenValue()))
                .defaultIfEmpty(false)
                .flatMap(disabled -> disabled ? reject(exchange) : chain.filter(exchange));
    }

    Mono<Boolean> isDisabled(String subject, String tokenValue) {
        if (subject == null || subject.isBlank()) {
            return Mono.just(false);
        }
        long now = System.currentTimeMillis();
        CachedStatus cached = cache.get(subject);
        if (cached != null && cached.expiresAtMillis() > now) {
            return Mono.just(cached.disabled());
        }
        return accounts.get()
                .uri("/api/v1/users/me/access")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenValue)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .bodyToMono(AccessStatus.class)
                .timeout(Duration.ofSeconds(2))
                .map(status -> {
                    remember(subject, status.disabled(), ttl);
                    return status.disabled();
                })
                .onErrorResume(error -> {
                    log.debug("Account access check unavailable; allowing request: {}", error.getClass().getSimpleName());
                    remember(subject, false, FAILURE_TTL);
                    return Mono.just(false);
                });
    }

    private void remember(String subject, boolean disabled, Duration lifetime) {
        if (cache.size() >= MAX_CACHE_ENTRIES) {
            long now = System.currentTimeMillis();
            cache.values().removeIf(entry -> entry.expiresAtMillis() <= now);
            if (cache.size() >= MAX_CACHE_ENTRIES) cache.clear();
        }
        cache.put(subject, new CachedStatus(disabled, System.currentTimeMillis() + lifetime.toMillis()));
    }

    record AccessStatus(boolean disabled) { }

    private Mono<Void> reject(ServerWebExchange exchange) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.valueOf("application/problem+json"));
        response.getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\", error_description=\"account disabled\"");
        String body = "{\"type\":\"about:blank\",\"title\":\"Account disabled\",\"status\":401,"
                + "\"detail\":\"This account has been disabled. Contact support if you think this is a mistake.\"}";
        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 30;
    }
}
