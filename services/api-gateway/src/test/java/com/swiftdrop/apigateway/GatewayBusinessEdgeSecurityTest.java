package com.swiftdrop.apigateway;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
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
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(GatewayBusinessEdgeSecurityTest.TestRoutesConfig.class)
public class GatewayBusinessEdgeSecurityTest {

    private static MockWebServer authServer;
    private static MockWebServer mockBackendServer;

    private static RSAKey trustedRsaKey;
    private static RSAKey untrustedRsaKey;
    private static String issuerUrl;

    @LocalServerPort
    private int port;

    private WebTestClient webTestClient;

    @Autowired
    private DefaultMerchantApiKeyValidator merchantApiKeyValidator;

    private static final String VALID_API_KEY = "swd_live_0123456789abcdef0123456789abcdef";
    private static final String REVOKED_API_KEY = "swd_live_revoked1234567890abcdef123456789";
    private static final String EXPIRED_API_KEY = "swd_live_expired1234567890abcdef123456789";
    private static final String PENDING_MERCHANT_KEY = "swd_live_pending1234567890abcdef123456789";
    private static final String WRONG_SCOPE_KEY = "swd_live_noscope1234567890abcdef123456789";

    @TestConfiguration
    public static class TestRoutesConfig {
        @Bean
        public RouteLocator testBusinessRouteLocator(RouteLocatorBuilder builder) {
            return builder.routes()
                    .route("test-backend-routes", r -> r
                            .path("/api/v1/parcels/**",
                                  "/api/v1/quotes/**",
                                  "/api/v1/tracking/**",
                                  "/api/v1/assignments/**",
                                  "/api/v1/merchant/**",
                                  "/api/v1/merchants/**",
                                  "/api/v1/admin/**",
                                  "/graphql")
                            .uri(mockBackendServer.url("/").toString()))
                    .build();
        }
    }

    @BeforeAll
    static void setUpServers() throws Exception {
        trustedRsaKey = new RSAKeyGenerator(2048)
                .keyID("swiftdrop-trusted-key")
                .generate();

        untrustedRsaKey = new RSAKeyGenerator(2048)
                .keyID("untrusted-key-id")
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
                return new MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody("{\"status\":\"success\",\"path\":\"" + request.getPath() + "\"}");
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
        registry.add("ACCOUNTS_SERVICE_URL", () -> mockBackendServer.url("").toString().replaceAll("/$", ""));
    }

    @BeforeEach
    void setUp() {
        this.webTestClient = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();

        merchantApiKeyValidator.clearKeys();
        merchantApiKeyValidator.registerKey(new DefaultMerchantApiKeyValidator.RegisteredMerchantKey(
                VALID_API_KEY,
                "merchant-tenant-1",
                "key-1",
                Set.of("parcels:create", "parcels:read"),
                "APPROVED",
                Instant.now().plus(30, ChronoUnit.DAYS),
                false
        ));
        merchantApiKeyValidator.registerKey(new DefaultMerchantApiKeyValidator.RegisteredMerchantKey(
                REVOKED_API_KEY,
                "merchant-tenant-1",
                "key-2",
                Set.of("parcels:create"),
                "APPROVED",
                Instant.now().plus(30, ChronoUnit.DAYS),
                true
        ));
        merchantApiKeyValidator.registerKey(new DefaultMerchantApiKeyValidator.RegisteredMerchantKey(
                EXPIRED_API_KEY,
                "merchant-tenant-1",
                "key-3",
                Set.of("parcels:create"),
                "APPROVED",
                Instant.now().minus(5, ChronoUnit.DAYS),
                false
        ));
        merchantApiKeyValidator.registerKey(new DefaultMerchantApiKeyValidator.RegisteredMerchantKey(
                PENDING_MERCHANT_KEY,
                "merchant-tenant-pending",
                "key-4",
                Set.of("parcels:create"),
                "PENDING",
                Instant.now().plus(30, ChronoUnit.DAYS),
                false
        ));
        merchantApiKeyValidator.registerKey(new DefaultMerchantApiKeyValidator.RegisteredMerchantKey(
                WRONG_SCOPE_KEY,
                "merchant-tenant-1",
                "key-5",
                Set.of("webhooks:manage"),
                "APPROVED",
                Instant.now().plus(30, ChronoUnit.DAYS),
                false
        ));
    }

    private String createJwt(List<String> roles, String issuer, String audience, boolean expired, RSAKey signingKey) throws Exception {
        Instant now = Instant.now();
        Instant exp = expired ? now.minus(5, ChronoUnit.MINUTES) : now.plus(1, ChronoUnit.HOURS);

        JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject("test-user-sub-12345")
                .issueTime(Date.from(now.minus(1, ChronoUnit.MINUTES)))
                .expirationTime(Date.from(exp))
                .claim("roles", roles)
                .claim("preferred_username", "testuser");

        if (audience != null) {
            builder.audience(List.of(audience));
        }

        JWTClaimsSet claimsSet = builder.build();
        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256)
                .keyID(signingKey.getKeyID())
                .build();

        SignedJWT signedJWT = new SignedJWT(header, claimsSet);
        signedJWT.sign(new RSASSASigner(signingKey.toRSAPrivateKey()));
        return signedJWT.serialize();
    }

    // =========================================================================
    // 1. JWT Rejection Tests
    // =========================================================================

    @Test
    @DisplayName("Protected routes reject missing JWT with HTTP 401 and RFC 7807 Problem Detail")
    void missingJwtOnProtectedRoutes_Returns401ProblemDetail() {
        // Customer booking
        webTestClient.post()
                .uri("/api/v1/parcels")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"pickupAddress\":\"Colombo\"}")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().contentType(MediaType.valueOf("application/problem+json"))
                .expectBody()
                .jsonPath("$.status").isEqualTo(401)
                .jsonPath("$.title").isEqualTo("Unauthorized");

        // Courier status patch
        webTestClient.patch()
                .uri("/api/v1/parcels/123/status")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"status\":\"PICKED_UP\"}")
                .exchange()
                .expectStatus().isUnauthorized();

        // Dispatcher assignment
        webTestClient.post()
                .uri("/api/v1/assignments")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"parcelId\":\"p-1\"}")
                .exchange()
                .expectStatus().isUnauthorized();

        // Admin merchant approval
        webTestClient.post()
                .uri("/api/v1/merchants/m-1/approve")
                .exchange()
                .expectStatus().isUnauthorized();

        // GraphQL Query API
        webTestClient.post()
                .uri("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"query\":\"{ customerDashboard { totalParcels } }\"}")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("Malformed JWT on protected route returns HTTP 401")
    void malformedJwt_Returns401() {
        webTestClient.post()
                .uri("/api/v1/parcels")
                .header(HttpHeaders.AUTHORIZATION, "Bearer invalid.malformed.token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().contentType(MediaType.valueOf("application/problem+json"));
    }

    @Test
    @DisplayName("JWT signed with untrusted signature returns HTTP 401")
    void untrustedSignature_Returns401() throws Exception {
        String token = createJwt(List.of("CUSTOMER"), issuerUrl, "swiftdrop-api", false, untrustedRsaKey);
        webTestClient.post()
                .uri("/api/v1/parcels")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("JWT with wrong issuer returns HTTP 401")
    void wrongIssuer_Returns401() throws Exception {
        String token = createJwt(List.of("CUSTOMER"), "http://attacker.com/realms/evil", "swiftdrop-api", false, trustedRsaKey);
        webTestClient.post()
                .uri("/api/v1/parcels")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("JWT with wrong audience returns HTTP 401")
    void wrongAudience_Returns401() throws Exception {
        String token = createJwt(List.of("CUSTOMER"), issuerUrl, "wrong-audience", false, trustedRsaKey);
        webTestClient.post()
                .uri("/api/v1/parcels")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("Expired JWT returns HTTP 401")
    void expiredJwt_Returns401() throws Exception {
        String token = createJwt(List.of("CUSTOMER"), issuerUrl, "swiftdrop-api", true, trustedRsaKey);
        webTestClient.post()
                .uri("/api/v1/parcels")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    // =========================================================================
    // 2. Role Authorization Tests
    // =========================================================================

    @Test
    @DisplayName("Courier status patch rejects CUSTOMER role with 403 Forbidden and accepts COURIER role")
    void courierStatusPatch_RoleEnforcement() throws Exception {
        String customerToken = createJwt(List.of("CUSTOMER"), issuerUrl, "swiftdrop-api", false, trustedRsaKey);
        String courierToken = createJwt(List.of("COURIER"), issuerUrl, "swiftdrop-api", false, trustedRsaKey);

        // Denied with CUSTOMER role
        webTestClient.patch()
                .uri("/api/v1/parcels/p-100/status")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"status\":\"IN_TRANSIT\"}")
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().contentType(MediaType.valueOf("application/problem+json"))
                .expectBody()
                .jsonPath("$.status").isEqualTo(403)
                .jsonPath("$.title").isEqualTo("Forbidden");

        // Allowed with COURIER role
        webTestClient.patch()
                .uri("/api/v1/parcels/p-100/status")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + courierToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"status\":\"IN_TRANSIT\"}")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    @DisplayName("Dispatcher assignment rejects COURIER role with 403 and accepts DISPATCHER or ADMIN role")
    void dispatcherAssignment_RoleEnforcement() throws Exception {
        String courierToken = createJwt(List.of("COURIER"), issuerUrl, "swiftdrop-api", false, trustedRsaKey);
        String dispatcherToken = createJwt(List.of("DISPATCHER"), issuerUrl, "swiftdrop-api", false, trustedRsaKey);
        String adminToken = createJwt(List.of("ADMIN"), issuerUrl, "swiftdrop-api", false, trustedRsaKey);

        // Denied with COURIER role
        webTestClient.post()
                .uri("/api/v1/assignments")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + courierToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"parcelId\":\"p-1\"}")
                .exchange()
                .expectStatus().isForbidden();

        // Allowed with DISPATCHER role
        webTestClient.post()
                .uri("/api/v1/assignments")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + dispatcherToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"parcelId\":\"p-1\"}")
                .exchange()
                .expectStatus().isOk();

        // Allowed with ADMIN role
        webTestClient.post()
                .uri("/api/v1/assignments")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"parcelId\":\"p-1\"}")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    @DisplayName("Admin merchant approval rejects DISPATCHER role with 403 and accepts ADMIN role")
    void adminMerchantApprove_RoleEnforcement() throws Exception {
        String dispatcherToken = createJwt(List.of("DISPATCHER"), issuerUrl, "swiftdrop-api", false, trustedRsaKey);
        String adminToken = createJwt(List.of("ADMIN"), issuerUrl, "swiftdrop-api", false, trustedRsaKey);

        webTestClient.post()
                .uri("/api/v1/merchants/m-1/approve")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + dispatcherToken)
                .exchange()
                .expectStatus().isForbidden();

        webTestClient.post()
                .uri("/api/v1/merchants/m-1/approve")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    @DisplayName("Customer parcel booking accepts CUSTOMER, MERCHANT, or ADMIN roles")
    void customerBooking_RoleEnforcement() throws Exception {
        String customerToken = createJwt(List.of("CUSTOMER"), issuerUrl, "swiftdrop-api", false, trustedRsaKey);
        String merchantToken = createJwt(List.of("MERCHANT"), issuerUrl, "swiftdrop-api", false, trustedRsaKey);

        webTestClient.post()
                .uri("/api/v1/parcels")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"weight\":2.5}")
                .exchange()
                .expectStatus().isOk();

        webTestClient.post()
                .uri("/api/v1/parcels")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + merchantToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"weight\":2.5}")
                .exchange()
                .expectStatus().isOk();
    }

    // =========================================================================
    // 3. Public Endpoints
    // =========================================================================

    @Test
    @DisplayName("Public tracking, quotes, and public SSE stream are accessible without authentication")
    void publicEndpoints_AccessibleWithoutAuth() {
        // Public tracking read
        webTestClient.get()
                .uri("/api/v1/tracking/SWD-20261023-A1B2C3")
                .exchange()
                .expectStatus().isOk();

        // Public quote calculator
        webTestClient.post()
                .uri("/api/v1/quotes")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"weight\":1.0,\"originZone\":\"ZONE_1\",\"destZone\":\"ZONE_1\"}")
                .exchange()
                .expectStatus().isOk();

        // Public SSE tracking stream
        webTestClient.get()
                .uri("/api/v1/tracking/SWD-20261023-A1B2C3/events")
                .exchange()
                .expectStatus().isOk();
    }

    // =========================================================================
    // 4. Merchant Machine API Key Authentication Tests
    // =========================================================================

    @Test
    @DisplayName("Merchant machine order API: missing credentials returns 401 Unauthorized")
    void merchantParcels_MissingCredentials_Returns401() {
        webTestClient.post()
                .uri("/api/v1/merchant/parcels")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"orderReference\":\"ORD-001\"}")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("Merchant machine order API: malformed API key syntax returns 401 Unauthorized")
    void merchantParcels_MalformedApiKey_Returns401() {
        webTestClient.post()
                .uri("/api/v1/merchant/parcels")
                .header("X-API-Key", "invalid_prefix_key")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"orderReference\":\"ORD-001\"}")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("Merchant machine order API: unknown/unregistered API key returns 401 Unauthorized")
    void merchantParcels_UnknownApiKey_Returns401() {
        webTestClient.post()
                .uri("/api/v1/merchant/parcels")
                .header("X-API-Key", "swd_live_unknown1234567890abcdef12345678")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"orderReference\":\"ORD-001\"}")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("Merchant machine order API: revoked API key returns 401 Unauthorized")
    void merchantParcels_RevokedApiKey_Returns401() {
        webTestClient.post()
                .uri("/api/v1/merchant/parcels")
                .header("X-API-Key", REVOKED_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"orderReference\":\"ORD-001\"}")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("Merchant machine order API: expired API key returns 401 Unauthorized")
    void merchantParcels_ExpiredApiKey_Returns401() {
        webTestClient.post()
                .uri("/api/v1/merchant/parcels")
                .header("X-API-Key", EXPIRED_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"orderReference\":\"ORD-001\"}")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("Merchant machine order API: unapproved merchant organization (PENDING) returns 403 Forbidden")
    void merchantParcels_UnapprovedMerchant_Returns403() {
        webTestClient.post()
                .uri("/api/v1/merchant/parcels")
                .header("X-API-Key", PENDING_MERCHANT_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"orderReference\":\"ORD-001\"}")
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().contentType(MediaType.valueOf("application/problem+json"))
                .expectBody()
                .jsonPath("$.status").isEqualTo(403);
    }

    @Test
    @DisplayName("Merchant machine order API: API key lacking parcels:create scope returns 403 Forbidden")
    void merchantParcels_WrongScope_Returns403() {
        webTestClient.post()
                .uri("/api/v1/merchant/parcels")
                .header("X-API-Key", WRONG_SCOPE_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"orderReference\":\"ORD-001\"}")
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    @DisplayName("Merchant machine order API: valid approved API key succeeds and authenticates as ROLE_MERCHANT")
    void merchantParcels_ValidApprovedKey_Succeeds() {
        webTestClient.post()
                .uri("/api/v1/merchant/parcels")
                .header("X-API-Key", VALID_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"orderReference\":\"ORD-001\"}")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    @DisplayName("Merchant API key cannot bypass JWT security on user profile or staff routes")
    void merchantApiKey_CannotBypassJwtOnNonMerchantRoutes() {
        // Attempting to access /api/v1/users/me with X-API-Key fails 401
        webTestClient.get()
                .uri("/api/v1/users/me")
                .header("X-API-Key", VALID_API_KEY)
                .exchange()
                .expectStatus().isUnauthorized();

        // Attempting to access dispatcher assignments with X-API-Key fails 401
        webTestClient.post()
                .uri("/api/v1/assignments")
                .header("X-API-Key", VALID_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"parcelId\":\"p-1\"}")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    // =========================================================================
    // 5. Header Sanitization and Spoofing Defense
    // =========================================================================

    @Test
    @DisplayName("Client-supplied identity and gRPC internal service headers are case-insensitively stripped")
    void forgedHeaders_CaseInsensitivelyStripped() throws Exception {
        while (mockBackendServer.takeRequest(50, java.util.concurrent.TimeUnit.MILLISECONDS) != null) {
            // drain prior requests
        }

        String token = createJwt(List.of("CUSTOMER"), issuerUrl, "swiftdrop-api", false, trustedRsaKey);

        webTestClient.post()
                .uri("/api/v1/parcels")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header("X-User-Id", "spoofed-user-id")
                .header("x-user-id", "duplicate-spoofed-id")
                .header("X-uSeR-Id", "mixed-case-spoof")
                .header("X-Roles", "ADMIN")
                .header("x-roles", "COURIER")
                .header("X-Tenant-Id", "spoofed-tenant-999")
                .header("x-keycloak-sub", "spoofed-sub-abc")
                .header("x-user-email", "forged@attacker.com")
                .header("x-swiftdrop-service-token", "forged-internal-service-token")
                .header("X-SwiftDrop-Actor-Sub", "forged-actor-sub")
                .header("x-swiftdrop-actor-roles", "ADMIN")
                .header("x-swiftdrop-correlation-id", "forged-trace-id")
                .header("Idempotency-Key", "7c9e6679-7425-40de-944b-e07fc1f90ae7")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"weight\":1.5}")
                .exchange()
                .expectStatus().isOk();

        RecordedRequest recorded = mockBackendServer.takeRequest();
        assertThat(recorded.getHeader("X-User-Id")).isNull();
        assertThat(recorded.getHeader("x-user-id")).isNull();
        assertThat(recorded.getHeader("X-uSeR-Id")).isNull();
        assertThat(recorded.getHeader("X-Roles")).isNull();
        assertThat(recorded.getHeader("x-roles")).isNull();
        assertThat(recorded.getHeader("X-Tenant-Id")).isNull();
        assertThat(recorded.getHeader("x-keycloak-sub")).isNull();
        assertThat(recorded.getHeader("x-user-email")).isNull();
        assertThat(recorded.getHeader("x-swiftdrop-service-token")).isNull();
        assertThat(recorded.getHeader("x-swiftdrop-actor-sub")).isNull();
        assertThat(recorded.getHeader("x-swiftdrop-actor-roles")).isNull();
        assertThat(recorded.getHeader("x-swiftdrop-correlation-id")).isNull();

        // Valid Authorization and Idempotency-Key are preserved
        assertThat(recorded.getHeader("Authorization")).isEqualTo("Bearer " + token);
        assertThat(recorded.getHeader("Idempotency-Key")).isEqualTo("7c9e6679-7425-40de-944b-e07fc1f90ae7");
    }
}
