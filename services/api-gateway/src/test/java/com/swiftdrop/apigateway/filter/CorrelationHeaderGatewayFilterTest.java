package com.swiftdrop.apigateway.filter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationHeaderGatewayFilterTest {

    private final CorrelationHeaderGatewayFilter filter = new CorrelationHeaderGatewayFilter();

    @Test
    @DisplayName("Valid incoming X-Correlation-ID is preserved and propagated downstream and in response")
    void testValidCorrelationIdPreserved() {
        String correlationId = "corr-pub-12345_XYZ";
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/quotes")
                .header("X-Correlation-ID", correlationId)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicReference<String> downstreamHeader = new AtomicReference<>();
        GatewayFilterChain chain = ex -> {
            downstreamHeader.set(ex.getRequest().getHeaders().getFirst(CorrelationHeaderGatewayFilter.CORRELATION_ID_HEADER));
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();

        assertThat(downstreamHeader.get()).isEqualTo(correlationId);
        assertThat(exchange.getResponse().getHeaders().getFirst(CorrelationHeaderGatewayFilter.CORRELATION_ID_HEADER))
                .isEqualTo(correlationId);
    }

    @Test
    @DisplayName("Valid incoming X-Request-ID fallback is normalized and forwarded")
    void testValidRequestIdFallback() {
        String requestId = "req-987654";
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/merchants")
                .header("X-Request-ID", requestId)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicReference<String> downstreamHeader = new AtomicReference<>();
        GatewayFilterChain chain = ex -> {
            downstreamHeader.set(ex.getRequest().getHeaders().getFirst(CorrelationHeaderGatewayFilter.CORRELATION_ID_HEADER));
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();

        assertThat(downstreamHeader.get()).isEqualTo(requestId);
        assertThat(exchange.getResponse().getHeaders().getFirst(CorrelationHeaderGatewayFilter.CORRELATION_ID_HEADER))
                .isEqualTo(requestId);
    }

    @Test
    @DisplayName("Missing correlation headers receive a freshly generated safe UUID")
    void testMissingHeaderGeneratesUuid() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/users/me").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicReference<String> downstreamHeader = new AtomicReference<>();
        GatewayFilterChain chain = ex -> {
            downstreamHeader.set(ex.getRequest().getHeaders().getFirst(CorrelationHeaderGatewayFilter.CORRELATION_ID_HEADER));
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();

        assertThat(downstreamHeader.get()).isNotNull();
        assertThat(UUID.fromString(downstreamHeader.get())).isNotNull();
        assertThat(exchange.getResponse().getHeaders().getFirst(CorrelationHeaderGatewayFilter.CORRELATION_ID_HEADER))
                .isEqualTo(downstreamHeader.get());
    }

    @Test
    @DisplayName("CRLF injection attempt in correlation header is rejected and replaced with safe UUID")
    void testCrlfInjectionRejected() {
        String maliciousHeader = "validId\r\nX-Injected-Header: attack\r\n";
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/quotes")
                .header("X-Correlation-ID", maliciousHeader)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicReference<String> downstreamHeader = new AtomicReference<>();
        GatewayFilterChain chain = ex -> {
            downstreamHeader.set(ex.getRequest().getHeaders().getFirst(CorrelationHeaderGatewayFilter.CORRELATION_ID_HEADER));
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();

        assertThat(downstreamHeader.get()).isNotNull();
        assertThat(downstreamHeader.get()).doesNotContain("\r").doesNotContain("\n").doesNotContain("attack");
        assertThat(UUID.fromString(downstreamHeader.get())).isNotNull();
    }

    @Test
    @DisplayName("Oversized identifier (>64 chars) is replaced with a safe UUID")
    void testOversizedHeaderReplaced() {
        String oversized = "c".repeat(65);
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/zones")
                .header("X-Correlation-ID", oversized)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicReference<String> downstreamHeader = new AtomicReference<>();
        GatewayFilterChain chain = ex -> {
            downstreamHeader.set(ex.getRequest().getHeaders().getFirst(CorrelationHeaderGatewayFilter.CORRELATION_ID_HEADER));
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();

        assertThat(downstreamHeader.get()).isNotNull();
        assertThat(downstreamHeader.get().length()).isLessThanOrEqualTo(64);
        assertThat(UUID.fromString(downstreamHeader.get())).isNotNull();
    }
}
