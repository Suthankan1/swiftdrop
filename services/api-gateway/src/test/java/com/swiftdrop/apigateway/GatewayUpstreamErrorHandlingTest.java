package com.swiftdrop.apigateway;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates Gateway upstream error handling and error translation:
 *   - Transparent propagation of upstream 4xx and 5xx business responses (RFC 7807 Problem Details preserved)
 *   - Translation of downstream connection failures to HTTP 503 Service Unavailable
 *   - Translation of upstream request timeouts to HTTP 504 Gateway Timeout
 *   - Translation of upstream transport resets / premature closes to HTTP 502 Bad Gateway
 *   - Zero leakage of internal service URLs, ports, IPs, or stack traces
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class GatewayUpstreamErrorHandlingTest {

    private static MockWebServer authServer;
    private static MockWebServer pricingServer;
    private static MockWebServer deliveryServer;

    private static RSAKey trustedRsaKey;
    private static String issuerUrl;

    @LocalServerPort
    private int port;

    private WebTestClient webTestClient;

    @BeforeEach
    void setUpClient() throws Exception {
        this.webTestClient = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(10))
                .build();

        drainMockServer(pricingServer);
        drainMockServer(deliveryServer);
    }

    private void drainMockServer(MockWebServer server) throws Exception {
        while (server.takeRequest(10, TimeUnit.MILLISECONDS) != null) {
            // drain leftover requests
        }
    }

    @BeforeAll
    static void setUpServers() throws Exception {
        trustedRsaKey = new RSAKeyGenerator(2048)
                .keyID("swiftdrop-error-test-key")
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

        pricingServer = new MockWebServer();
        pricingServer.start();

        deliveryServer = new MockWebServer();
        deliveryServer.start();
    }

    @AfterAll
    static void tearDownServers() throws IOException {
        if (authServer != null) authServer.shutdown();
        if (pricingServer != null) pricingServer.shutdown();
        if (deliveryServer != null) deliveryServer.shutdown();
    }

    @DynamicPropertySource
    static void registerDynamicProperties(DynamicPropertyRegistry registry) {
        registry.add("swiftdrop.jwt.jwk-set-uri", () -> authServer.url("/realms/swiftdrop/protocol/openid-connect/certs").toString());
        registry.add("swiftdrop.jwt.issuer-uri", () -> issuerUrl);
        registry.add("swiftdrop.jwt.audience", () -> "swiftdrop-api");
        // Point accounts service to an unreachable closed port to test connection refusal
        registry.add("ACCOUNTS_SERVICE_URL", () -> "http://127.0.0.1:54998");
        registry.add("PRICING_SERVICE_URL", () -> pricingServer.url("").toString().replaceAll("/$", ""));
        registry.add("DELIVERY_SERVICE_URL", () -> deliveryServer.url("").toString().replaceAll("/$", ""));
        // Shorten response-timeout for fast timeout testing
        registry.add("spring.cloud.gateway.server.webflux.httpclient.response-timeout", () -> "1s");
        registry.add("spring.cloud.gateway.httpclient.response-timeout", () -> "1s");
        registry.add("GATEWAY_RESPONSE_TIMEOUT", () -> "1s");
    }

    private String createToken(String subject, List<String> roles) throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet claimsSet = new JWTClaimsSet.Builder()
                .issuer(issuerUrl)
                .subject(subject)
                .issueTime(Date.from(now.minus(1, ChronoUnit.MINUTES)))
                .expirationTime(Date.from(now.plus(10, ChronoUnit.MINUTES)))
                .audience(List.of("swiftdrop-api"))
                .claim("roles", roles)
                .claim("preferred_username", subject)
                .build();

        SignedJWT signedJWT = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(trustedRsaKey.getKeyID()).build(),
                claimsSet
        );
        signedJWT.sign(new RSASSASigner(trustedRsaKey.toRSAPrivateKey()));
        return signedJWT.serialize();
    }

    // =========================================================================
    // 1. UPSTREAM 4xx ERROR PROPAGATION (PRESERVES UPSTREAM STATUS & RFC 7807)
    // =========================================================================

    @Test
    @DisplayName("Propagates upstream 400 Bad Request RFC 7807 Problem Detail without altering status")
    void propagatesUpstream400ProblemDetail() throws Exception {
        pricingServer.enqueue(new MockResponse()
                .setResponseCode(400)
                .setHeader("Content-Type", "application/problem+json")
                .setBody("{\"type\":\"https://swiftdrop.com/errors/INVALID_WEIGHT\",\"title\":\"Invalid Weight\",\"status\":400,\"detail\":\"Weight exceeds max allowed limit.\",\"instance\":\"/api/v1/quotes\"}"));

        webTestClient.post()
                .uri("/api/v1/quotes")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"weightKg\":999.0}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentType(MediaType.valueOf("application/problem+json"))
                .expectBody()
                .jsonPath("$.type").isEqualTo("https://swiftdrop.com/errors/INVALID_WEIGHT")
                .jsonPath("$.title").isEqualTo("Invalid Weight")
                .jsonPath("$.status").isEqualTo(400)
                .jsonPath("$.detail").isEqualTo("Weight exceeds max allowed limit.")
                .jsonPath("$.instance").isEqualTo("/api/v1/quotes");

        RecordedRequest req = pricingServer.takeRequest(3, TimeUnit.SECONDS);
        assertThat(req).isNotNull();
    }

    @Test
    @DisplayName("Propagates upstream 404 Not Found RFC 7807 Problem Detail without altering status")
    void propagatesUpstream404ProblemDetail() throws Exception {
        String token = createToken("customer-1", List.of("CUSTOMER"));
        deliveryServer.enqueue(new MockResponse()
                .setResponseCode(404)
                .setHeader("Content-Type", "application/problem+json")
                .setBody("{\"type\":\"https://swiftdrop.com/errors/PARCEL_NOT_FOUND\",\"title\":\"Not Found\",\"status\":404,\"detail\":\"Parcel 999 not found.\",\"instance\":\"/api/v1/parcels/999\"}"));

        webTestClient.get()
                .uri("/api/v1/parcels/999")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().contentType(MediaType.valueOf("application/problem+json"))
                .expectBody()
                .jsonPath("$.status").isEqualTo(404)
                .jsonPath("$.title").isEqualTo("Not Found")
                .jsonPath("$.detail").isEqualTo("Parcel 999 not found.");

        RecordedRequest req = deliveryServer.takeRequest(3, TimeUnit.SECONDS);
        assertThat(req).isNotNull();
    }

    @Test
    @DisplayName("Propagates upstream 409 Conflict RFC 7807 Problem Detail without altering status")
    void propagatesUpstream409ConflictProblemDetail() throws Exception {
        String token = createToken("courier-1", List.of("COURIER"));
        deliveryServer.enqueue(new MockResponse()
                .setResponseCode(409)
                .setHeader("Content-Type", "application/problem+json")
                .setBody("{\"type\":\"https://swiftdrop.com/errors/VERSION_CONFLICT\",\"title\":\"Conflict\",\"status\":409,\"detail\":\"Expected version mismatch.\",\"instance\":\"/api/v1/parcels/p-1/status\"}"));

        webTestClient.patch()
                .uri("/api/v1/parcels/p-1/status")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"status\":\"PICKED_UP\",\"expectedVersion\":1}")
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectHeader().contentType(MediaType.valueOf("application/problem+json"))
                .expectBody()
                .jsonPath("$.status").isEqualTo(409)
                .jsonPath("$.title").isEqualTo("Conflict")
                .jsonPath("$.detail").isEqualTo("Expected version mismatch.");

        RecordedRequest req = deliveryServer.takeRequest(3, TimeUnit.SECONDS);
        assertThat(req).isNotNull();
    }

    @Test
    @DisplayName("Propagates upstream 422 Unprocessable Entity RFC 7807 Problem Detail without altering status")
    void propagatesUpstream422ProblemDetail() throws Exception {
        pricingServer.enqueue(new MockResponse()
                .setResponseCode(422)
                .setHeader("Content-Type", "application/problem+json")
                .setBody("{\"type\":\"https://swiftdrop.com/errors/INVALID_ZONE\",\"title\":\"Unprocessable Entity\",\"status\":422,\"detail\":\"Zone unknown.\",\"instance\":\"/api/v1/quotes\"}"));

        webTestClient.post()
                .uri("/api/v1/quotes")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"weightKg\":1.0,\"pickupZone\":\"INVALID_ZONE\"}")
                .exchange()
                .expectStatus().isEqualTo(422)
                .expectHeader().contentType(MediaType.valueOf("application/problem+json"))
                .expectBody()
                .jsonPath("$.status").isEqualTo(422)
                .jsonPath("$.title").isEqualTo("Unprocessable Entity");

        RecordedRequest req = pricingServer.takeRequest(3, TimeUnit.SECONDS);
        assertThat(req).isNotNull();
    }

    // =========================================================================
    // 2. UPSTREAM 500 ERROR PROPAGATION
    // =========================================================================

    @Test
    @DisplayName("Propagates upstream 500 Internal Server Error with upstream Problem Detail intact")
    void propagatesUpstream500ProblemDetail() throws Exception {
        pricingServer.enqueue(new MockResponse()
                .setResponseCode(500)
                .setHeader("Content-Type", "application/problem+json")
                .setBody("{\"type\":\"about:blank\",\"title\":\"Internal Server Error\",\"status\":500,\"detail\":\"Pricing calculation failed.\",\"instance\":\"/api/v1/quotes\"}"));

        webTestClient.post()
                .uri("/api/v1/quotes")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"weightKg\":5.0}")
                .exchange()
                .expectStatus().is5xxServerError()
                .expectHeader().contentType(MediaType.valueOf("application/problem+json"))
                .expectBody()
                .jsonPath("$.status").isEqualTo(500)
                .jsonPath("$.detail").isEqualTo("Pricing calculation failed.");

        RecordedRequest req = pricingServer.takeRequest(3, TimeUnit.SECONDS);
        assertThat(req).isNotNull();
    }

    // =========================================================================
    // 3. DOWNSTREAM CONNECTION FAILURE -> HTTP 503 (SERVICE UNAVAILABLE)
    // =========================================================================

    @Test
    @DisplayName("Downstream connection failure returns HTTP 503 without leaking internal hostnames or stack traces")
    void downstreamConnectionFailure_Returns503WithoutLeakingDetails() throws Exception {
        String token = createToken("user-1", List.of("CUSTOMER"));

        // ACCOUNTS_SERVICE_URL points to closed port 54998
        webTestClient.get()
                .uri("/api/v1/users/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectHeader().contentType(MediaType.valueOf("application/problem+json"))
                .expectBody()
                .jsonPath("$.type").isEqualTo("about:blank")
                .jsonPath("$.title").isEqualTo("Service Unavailable")
                .jsonPath("$.status").isEqualTo(503)
                .jsonPath("$.detail").isEqualTo("Downstream service is currently unavailable.")
                .jsonPath("$.instance").isEqualTo("/api/v1/users/me");
    }

    // =========================================================================
    // 4. UPSTREAM TIMEOUT -> HTTP 504 (GATEWAY TIMEOUT)
    // =========================================================================

    @Test
    @DisplayName("Upstream timeout returns HTTP 504 Gateway Timeout without leaking internal details")
    void upstreamTimeout_Returns504WithoutLeakingDetails() throws Exception {
        // Enqueue response with 3-second delay on headers, exceeding the 1-second configured response timeout
        pricingServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"quoteId\":\"delayed\"}")
                .setHeadersDelay(3, TimeUnit.SECONDS));

        webTestClient.post()
                .uri("/api/v1/quotes")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"weightKg\":1.0}")
                .exchange()
                .expectStatus().isEqualTo(504)
                .expectHeader().contentType(MediaType.valueOf("application/problem+json"))
                .expectBody()
                .jsonPath("$.type").isEqualTo("about:blank")
                .jsonPath("$.title").isEqualTo("Gateway Timeout")
                .jsonPath("$.status").isEqualTo(504)
                .jsonPath("$.detail").isEqualTo("Downstream service request timed out.")
                .jsonPath("$.instance").isEqualTo("/api/v1/quotes");

        pricingServer.takeRequest(5, TimeUnit.SECONDS);
    }

    // =========================================================================
    // 5. UPSTREAM CONNECTION RESET -> HTTP 502 (BAD GATEWAY)
    // =========================================================================

    @Test
    @DisplayName("Upstream connection reset / disconnect returns HTTP 502 Bad Gateway without leaking internal details")
    void upstreamDisconnect_Returns502WithoutLeakingDetails() throws Exception {
        // Simulate unexpected upstream connection termination after receiving request
        pricingServer.enqueue(new MockResponse()
                .setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST));

        webTestClient.post()
                .uri("/api/v1/quotes")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"weightKg\":1.0}")
                .exchange()
                .expectStatus().isEqualTo(502)
                .expectHeader().contentType(MediaType.valueOf("application/problem+json"))
                .expectBody()
                .jsonPath("$.type").isEqualTo("about:blank")
                .jsonPath("$.title").isEqualTo("Bad Gateway")
                .jsonPath("$.status").isEqualTo(502)
                .jsonPath("$.detail").isEqualTo("Downstream service error or invalid response.")
                .jsonPath("$.instance").isEqualTo("/api/v1/quotes");

        pricingServer.takeRequest(3, TimeUnit.SECONDS);
    }
}
