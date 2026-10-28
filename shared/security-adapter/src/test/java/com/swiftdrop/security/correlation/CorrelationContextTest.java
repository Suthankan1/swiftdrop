package com.swiftdrop.security.correlation;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationContextTest {

    @BeforeEach
    @AfterEach
    void cleanUp() {
        CorrelationContext.clear();
        MDC.clear();
    }

    @Test
    @DisplayName("Valid safe correlation identifier is preserved as-is")
    void testValidCorrelationIdPreserved() {
        String validId = "req-12345-abcd_EFGH";
        String result = CorrelationContext.set(validId);

        assertThat(result).isEqualTo(validId);
        assertThat(CorrelationContext.get()).contains(validId);
        assertThat(MDC.get(CorrelationContext.MDC_KEY_CORRELATION_ID)).isEqualTo(validId);
    }

    @Test
    @DisplayName("Missing, null, or empty correlation ID generates a clean UUID")
    void testMissingCorrelationIdGeneratesUuid() {
        String nullResult = CorrelationContext.set(null);
        assertThat(nullResult).isNotNull();
        assertThat(UUID.fromString(nullResult)).isNotNull();

        String emptyResult = CorrelationContext.sanitizeOrGenerate("   ");
        assertThat(emptyResult).isNotNull();
        assertThat(UUID.fromString(emptyResult)).isNotNull();
    }

    @Test
    @DisplayName("Oversized identifier exceeding 64 characters is replaced with a safe UUID")
    void testOversizedCorrelationIdReplaced() {
        String oversized = "a".repeat(65);
        String result = CorrelationContext.sanitizeOrGenerate(oversized);

        assertThat(result).isNotEqualTo(oversized);
        assertThat(result.length()).isLessThanOrEqualTo(64);
        assertThat(UUID.fromString(result)).isNotNull();
    }

    @Test
    @DisplayName("CRLF header injection attempts are rejected and replaced with safe UUIDs")
    void testCrlfInjectionRejected() {
        String injected = "validId\r\nX-Injected-Header: evil\nSet-Cookie: session=123";
        String result = CorrelationContext.sanitizeOrGenerate(injected);

        assertThat(result).doesNotContain("\r").doesNotContain("\n").doesNotContain("evil");
        assertThat(UUID.fromString(result)).isNotNull();
    }

    @Test
    @DisplayName("Malicious control characters, null bytes, and script tags are rejected")
    void testMaliciousCharactersRejected() {
        String xss = "<script>alert(1)</script>";
        String result = CorrelationContext.sanitizeOrGenerate(xss);
        assertThat(result).doesNotContain("<script>");
        assertThat(UUID.fromString(result)).isNotNull();

        String nullByte = "prefix\0suffix";
        String nullResult = CorrelationContext.sanitizeOrGenerate(nullByte);
        assertThat(nullResult).doesNotContain("\0");
        assertThat(UUID.fromString(nullResult)).isNotNull();
    }

    @Test
    @DisplayName("Correlation context clears ThreadLocal and MDC without residual leak")
    void testClearRemovesThreadLocalAndMdc() {
        CorrelationContext.set("test-corr-id-999");
        assertThat(CorrelationContext.get()).isPresent();
        assertThat(MDC.get(CorrelationContext.MDC_KEY_CORRELATION_ID)).isNotNull();

        CorrelationContext.clear();
        assertThat(CorrelationContext.get()).isEmpty();
        assertThat(MDC.get(CorrelationContext.MDC_KEY_CORRELATION_ID)).isNull();
    }

    @Test
    @DisplayName("Thread isolation: concurrent threads maintain strictly distinct correlation IDs")
    void testThreadIsolation() throws Exception {
        int threadCount = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        ConcurrentMap<Integer, String> observedIds = new ConcurrentHashMap<>();

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            final String assignedId = "thread-worker-" + index + "-" + UUID.randomUUID();
            executor.submit(() -> {
                try {
                    startLatch.await();
                    CorrelationContext.set(assignedId);
                    Thread.sleep(15);
                    observedIds.put(index, CorrelationContext.get().orElse(""));
                } catch (Exception ignored) {
                } finally {
                    CorrelationContext.clear();
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertThat(doneLatch.await(5, TimeUnit.SECONDS)).isTrue();
        executor.shutdownNow();

        assertThat(observedIds).hasSize(threadCount);
        for (int i = 0; i < threadCount; i++) {
            assertThat(observedIds.get(i)).startsWith("thread-worker-" + i + "-");
        }
    }

    @Test
    @DisplayName("CorrelationIdFilter processes incoming header, echoes response header, and cleans up")
    void testCorrelationIdFilter() throws Exception {
        CorrelationIdFilter filter = new CorrelationIdFilter();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationContext.CORRELATION_ID_HEADER, "client-req-001");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = new MockFilterChain();

        AtomicReference<String> insideChainCorrelationId = new AtomicReference<>();
        AtomicReference<String> insideChainMdc = new AtomicReference<>();

        MockFilterChain capturingChain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                insideChainCorrelationId.set(CorrelationContext.get().orElse(null));
                insideChainMdc.set(MDC.get(CorrelationContext.MDC_KEY_CORRELATION_ID));
            }
        };

        filter.doFilter(request, response, capturingChain);

        assertThat(insideChainCorrelationId.get()).isEqualTo("client-req-001");
        assertThat(insideChainMdc.get()).isEqualTo("client-req-001");
        assertThat(response.getHeader(CorrelationContext.CORRELATION_ID_HEADER)).isEqualTo("client-req-001");

        // After filter completes, context MUST be cleared
        assertThat(CorrelationContext.get()).isEmpty();
        assertThat(MDC.get(CorrelationContext.MDC_KEY_CORRELATION_ID)).isNull();
    }

    @Test
    @DisplayName("CorrelationIdFilter falls back to X-Request-ID when X-SwiftDrop-Correlation-ID is absent")
    void testCorrelationIdFilterFallback() throws Exception {
        CorrelationIdFilter filter = new CorrelationIdFilter();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Request-ID", "req-id-777");
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<String> insideChainCorrelationId = new AtomicReference<>();
        MockFilterChain capturingChain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                insideChainCorrelationId.set(CorrelationContext.get().orElse(null));
            }
        };

        filter.doFilter(request, response, capturingChain);

        assertThat(insideChainCorrelationId.get()).isEqualTo("req-id-777");
        assertThat(response.getHeader(CorrelationContext.CORRELATION_ID_HEADER)).isEqualTo("req-id-777");
    }
}
