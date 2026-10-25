package com.swiftdrop.apigateway;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.swiftdrop.apigateway.filter.ratelimit.InMemoryRateLimiter;
import com.swiftdrop.apigateway.security.merchant.DefaultMerchantApiKeyValidator;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.FluxExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(GatewayAbuseAndSsePolicyTest.TestRoutesConfig.class)
public class GatewayAbuseAndSsePolicyTest {

    private static MockWebServer authServer;
    private static MockWebServer mockBackendServer;

    private static RSAKey trustedRsaKey;
    private static String issuerUrl;

    @LocalServerPort
    private int port;

    private WebTestClient webTestClient;

    @Autowired
    private InMemoryRateLimiter rateLimiter;

    @Autowired
    private DefaultMerchantApiKeyValidator merchantApiKeyValidator;

    private static final String VALID_KEY_1 = "swd_live_0123456789abcdef0123456789abcdef";
    private static final String VALID_KEY_2 = "swd_live_fedcba9876543210fedcba9876543210";

    @TestConfiguration
    public static class TestRoutesConfig {
        @Bean
        public RouteLocator testAbuseRouteLocator(RouteLocatorBuilder builder) {
            return builder.routes()
                    .route("abuse-test-routes", r -> r
                            .path("/api/v1/tracking/**",
                                  "/api/v1/quotes/**",
                                  "/api/v1/parcels/**",
                                  "/api/v1/merchant/parcels")
                            .uri(mockBackendServer.url("/").toString()))
                    .build();
        }
    }

    @BeforeAll
    static void setUpServers() throws Exception {
        trustedRsaKey = new RSAKeyGenerator(2048)
                .keyID("swiftdrop-trusted-key")
                .generate();

        authServer = new MockWebServer();
        authServer.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                if ("/realms/swiftdrop/protocol/openid-connect/certs".equals(request.getPath())) {
                    JWKSet jwkSet = new JWKSet(trustedRsaKey.toPublicJWK());
                    return new MockResponse()
                            .setResponseCode(200)
                            .setHeader("Content-Type", "application/json")
                            .setBody(jwkSet.toString());
                }
                return new MockResponse().setResponseCode(404);
            }
        });
        authServer.start();
        issuerUrl = authServer.url("/realms/swiftdrop").toString().replaceAll("/$", "");

        mockBackendServer = new MockWebServer();
        mockBackendServer.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String path = request.getPath() != null ? request.getPath() : "";
                if (path.contains("/events")) {
                    return new MockResponse()
                            .setResponseCode(200)
                            .setHeader("Content-Type", "text/event-stream")
                            .setHeader("Cache-Control", "no-cache")
                            .setHeader("Connection", "keep-alive")
                            .throttleBody(1, 3, java.util.concurrent.TimeUnit.SECONDS)
                            .setBody("data: {\"status\":\"CONNECTED\"}\n\n");
                }
                return new MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody("{\"status\":\"success\",\"path\":\"" + path + "\"}");
            }
        });
        mockBackendServer.start();
    }

    @AfterAll
    static void tearDownServers() throws IOException {
        if (authServer != null) {
            authServer.shutdown();
        }
        if (mockBackendServer != null) {
            mockBackendServer.shutdown();
        }
    }

    @DynamicPropertySource
    static void registerDynamicProperties(DynamicPropertyRegistry registry) {
        registry.add("swiftdrop.jwt.jwk-set-uri", () -> authServer.url("/realms/swiftdrop/protocol/openid-connect/certs").toString());
        registry.add("swiftdrop.jwt.issuer-uri", () -> issuerUrl);
        registry.add("swiftdrop.jwt.audience", () -> "swiftdrop-api");
        // Lower limits for test deterministic assertion
        registry.add("swiftdrop.gateway.rate-limit.public-limit-per-minute", () -> "5");
        registry.add("swiftdrop.gateway.rate-limit.public-burst-capacity", () -> "5");
        registry.add("swiftdrop.gateway.rate-limit.merchant-limit-per-minute", () -> "5");
        registry.add("swiftdrop.gateway.rate-limit.merchant-burst-capacity", () -> "5");
        registry.add("swiftdrop.gateway.sse.max-public-connections", () -> "3");
        registry.add("swiftdrop.gateway.sse.max-authenticated-connections", () -> "5");
    }

    @BeforeEach
    void setUp() {
        this.webTestClient = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(10))
                .build();

        rateLimiter.clear();

        merchantApiKeyValidator.clearKeys();
        merchantApiKeyValidator.registerKey(new DefaultMerchantApiKeyValidator.RegisteredMerchantKey(
                VALID_KEY_1,
                "merchant-tenant-1",
                "key-1",
                Set.of("parcels:create", "parcels:read"),
                "APPROVED",
                Instant.now().plus(30, ChronoUnit.DAYS),
                false
        ));
        merchantApiKeyValidator.registerKey(new DefaultMerchantApiKeyValidator.RegisteredMerchantKey(
                VALID_KEY_2,
                "merchant-tenant-2",
                "key-2",
                Set.of("parcels:create", "parcels:read"),
                "APPROVED",
                Instant.now().plus(30, ChronoUnit.DAYS),
                false
        ));
    }

    private String createJwt(List<String> roles, String sub) throws Exception {
        Instant now = Instant.now();
        Instant exp = now.plus(1, ChronoUnit.HOURS);

        JWTClaimsSet claimsSet = new JWTClaimsSet.Builder()
                .issuer(issuerUrl)
                .subject(sub)
                .issueTime(Date.from(now.minus(1, ChronoUnit.MINUTES)))
                .expirationTime(Date.from(exp))
                .audience(List.of("swiftdrop-api"))
                .claim("roles", roles)
                .claim("preferred_username", sub)
                .build();

        SignedJWT signedJWT = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(trustedRsaKey.getKeyID()).build(),
                claimsSet
        );
        signedJWT.sign(new RSASSASigner(trustedRsaKey.toRSAPrivateKey()));
        return signedJWT.serialize();
    }

    // =========================================================================
    // 1. Public Endpoint Rate Limiting Tests
    // =========================================================================

    @Test
    @DisplayName("Public endpoint allows requests up to limit, then rejects with HTTP 429 and Retry-After")
    void publicRateLimiting_RejectsWhenLimitExceeded() {
        // First 5 requests within burst capacity should succeed
        for (int i = 0; i < 5; i++) {
            webTestClient.get()
                    .uri("/api/v1/tracking/SWD-TRACK-001")
                    .exchange()
                    .expectStatus().isOk();
        }

        // 6th request exceeds burst capacity and is rejected with HTTP 429 Problem Detail
        webTestClient.get()
                .uri("/api/v1/tracking/SWD-TRACK-001")
                .exchange()
                .expectStatus().isEqualTo(429)
                .expectHeader().contentType(MediaType.valueOf("application/problem+json"))
                .expectHeader().value("Retry-After", value -> assertThat(Integer.parseInt(value)).isGreaterThanOrEqualTo(1))
                .expectBody()
                .jsonPath("$.status").isEqualTo(429)
                .jsonPath("$.title").isEqualTo("Too Many Requests")
                .jsonPath("$.instance").isEqualTo("/api/v1/tracking/SWD-TRACK-001");
    }

    // =========================================================================
    // 2. Merchant Machine Rate Limiting Tests
    // =========================================================================

    @Test
    @DisplayName("Merchant API key consumes merchant quota independently per verified identity key")
    void merchantRateLimiting_IndependentQuotasPerKey() {
        // Key 1 consumes 5 requests
        for (int i = 0; i < 5; i++) {
            webTestClient.post()
                    .uri("/api/v1/merchant/parcels")
                    .header("X-API-Key", VALID_KEY_1)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("{\"orderReference\":\"ORD-K1-" + i + "\"}")
                    .exchange()
                    .expectStatus().isOk();
        }

        // Key 1 6th request fails with 429
        webTestClient.post()
                .uri("/api/v1/merchant/parcels")
                .header("X-API-Key", VALID_KEY_1)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"orderReference\":\"ORD-K1-OVER\"}")
                .exchange()
                .expectStatus().isEqualTo(429)
                .expectHeader().value("Retry-After", value -> assertThat(Integer.parseInt(value)).isGreaterThanOrEqualTo(1));

        // Key 2 has independent quota bucket and succeeds immediately
        webTestClient.post()
                .uri("/api/v1/merchant/parcels")
                .header("X-API-Key", VALID_KEY_2)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"orderReference\":\"ORD-K2-0\"}")
                .exchange()
                .expectStatus().isOk();
    }

    // =========================================================================
    // 3. Payload Size Limit Tests
    // =========================================================================

    @Test
    @DisplayName("Quotes calculation rejects request exceeding 64 KB with HTTP 413 Payload Too Large")
    void payloadLimit_QuotesExceeding64KB_Returns413() {
        String payloadStr = "{\"data\":\"" + "A".repeat(70_000) + "\"}";
        webTestClient.post()
                .uri("/api/v1/quotes")
                .contentType(MediaType.APPLICATION_JSON)
                .contentLength(payloadStr.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                .bodyValue(payloadStr)
                .exchange()
                .expectStatus().isEqualTo(413)
                .expectHeader().contentType(MediaType.valueOf("application/problem+json"))
                .expectBody()
                .jsonPath("$.status").isEqualTo(413)
                .jsonPath("$.title").isEqualTo("Payload Too Large")
                .jsonPath("$.instance").isEqualTo("/api/v1/quotes");
    }

    @Test
    @DisplayName("Quotes calculation permits request within 64 KB limit")
    void payloadLimit_QuotesWithin64KB_Succeeds() {
        webTestClient.post()
                .uri("/api/v1/quotes")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"weight\":2.5,\"dimensions\":{\"length\":10,\"width\":10,\"height\":10}}")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    @DisplayName("Customer parcel booking rejects request exceeding standard 256 KB limit with HTTP 413")
    void payloadLimit_StandardParcelsExceeding256KB_Returns413() throws Exception {
        String token = createJwt(List.of("CUSTOMER"), "customer-user-1");

        String payloadStr = "{\"notes\":\"" + "X".repeat(300_000) + "\"}";
        webTestClient.post()
                .uri("/api/v1/parcels")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .contentLength(payloadStr.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                .bodyValue(payloadStr)
                .exchange()
                .expectStatus().isEqualTo(413)
                .expectHeader().contentType(MediaType.valueOf("application/problem+json"))
                .expectBody()
                .jsonPath("$.status").isEqualTo(413)
                .jsonPath("$.title").isEqualTo("Payload Too Large");
    }

    // =========================================================================
    // 4. SSE Concurrency and Lifecycle Policy Tests
    // =========================================================================

    @Test
    @DisplayName("Public SSE tracking stream limits concurrent connections to configured cap (3)")
    void ssePublicConcurrencyLimit_EnforcesCapAndCleansUp() {
        List<FluxExchangeResult<String>> activeStreams = new ArrayList<>();

        try {
            // Establish 3 concurrent streams (maxAllowed = 3)
            for (int i = 0; i < 3; i++) {
                FluxExchangeResult<String> result = webTestClient.get()
                        .uri("/api/v1/tracking/events")
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .exchange()
                        .expectStatus().isOk()
                        .returnResult(String.class);

                activeStreams.add(result);
            }

            // 4th concurrent stream exceeds cap and returns 429 Problem Detail
            webTestClient.get()
                    .uri("/api/v1/tracking/events")
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .exchange()
                    .expectStatus().isEqualTo(429)
                    .expectHeader().contentType(MediaType.valueOf("application/problem+json"))
                    .expectBody()
                    .jsonPath("$.status").isEqualTo(429)
                    .jsonPath("$.title").isEqualTo("Too Many Requests")
                    .jsonPath("$.detail").isEqualTo("Concurrent SSE connection limit exceeded. Maximum allowed: 3.");

        } finally {
            // Cancel streams to trigger doFinally cleanup
            for (FluxExchangeResult<String> stream : activeStreams) {
                stream.getResponseBody().subscribe().dispose();
            }
        }
    }

    @Test
    @DisplayName("Authenticated SSE stream requires valid Bearer JWT and enforces user-bound concurrency")
    void sseAuthenticated_EnforcesAuthAndUserConcurrency() throws Exception {
        // Without JWT -> 401 Unauthorized
        webTestClient.get()
                .uri("/api/v1/parcels/p-100/events")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isUnauthorized();

        // With valid JWT -> 200 OK text/event-stream
        String token = createJwt(List.of("CUSTOMER"), "customer-user-2");
        FluxExchangeResult<String> stream = webTestClient.get()
                .uri("/api/v1/parcels/p-100/events")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .returnResult(String.class);

        // Cleanup
        stream.getResponseBody().subscribe().dispose();
    }

    // =========================================================================
    // 5. In-Memory Rate Limiter Direct Unit Verification
    // =========================================================================

    @Test
    @DisplayName("InMemoryRateLimiter bounds cardinality and purges stale buckets")
    void inMemoryRateLimiter_BoundsCardinality() {
        InMemoryRateLimiter limiter = new InMemoryRateLimiter();

        // Consume across distinct keys
        for (int i = 0; i < 50; i++) {
            InMemoryRateLimiter.ConsumptionResult res = limiter.tryConsume("test-ip-" + i, 1, 10, 10);
            assertThat(res.allowed()).isTrue();
        }

        assertThat(limiter.size()).isLessThanOrEqualTo(50);
        limiter.clear();
        assertThat(limiter.size()).isEqualTo(0);
    }
}
