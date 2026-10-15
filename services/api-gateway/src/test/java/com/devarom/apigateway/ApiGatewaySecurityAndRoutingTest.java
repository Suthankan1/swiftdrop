package com.devarom.apigateway;

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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class ApiGatewaySecurityAndRoutingTest {

    private static MockWebServer authServer;
    private static MockWebServer accountsServer;

    private static RSAKey trustedRsaKey;
    private static RSAKey untrustedRsaKey;
    private static String issuerUrl;

    @LocalServerPort
    private int port;

    private WebTestClient webTestClient;

    @BeforeEach
    void setUpClient() {
        this.webTestClient = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();
    }

    @BeforeAll
    static void setUpServers() throws Exception {
        trustedRsaKey = new RSAKeyGenerator(2048)
                .keyID("trusted-key-id")
                .generate();

        untrustedRsaKey = new RSAKeyGenerator(2048)
                .keyID("untrusted-key-id")
                .generate();

        authServer = new MockWebServer();
        authServer.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                if ("/protocol/openid-connect/certs".equals(request.getPath())) {
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
        issuerUrl = authServer.url("/realms/devarom").toString().replaceAll("/$", "");

        accountsServer = new MockWebServer();
        accountsServer.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                if ("/api/v1/users/me".equals(request.getPath())) {
                    return new MockResponse()
                            .setResponseCode(200)
                            .setHeader("Content-Type", "application/json")
                            .setBody("{\"id\":\"7c9e6679-7425-40de-944b-e07fc1f90ae7\",\"keycloakSub\":\"sub-123\",\"email\":\"customer@example.com\",\"roles\":[\"CUSTOMER\"]}");
                }
                return new MockResponse().setResponseCode(404);
            }
        });
        accountsServer.start();
    }

    @AfterAll
    static void tearDownServers() throws IOException {
        if (authServer != null) {
            authServer.shutdown();
        }
        if (accountsServer != null) {
            accountsServer.shutdown();
        }
    }

    @DynamicPropertySource
    static void registerDynamicProperties(DynamicPropertyRegistry registry) {
        registry.add("devarom.jwt.jwk-set-uri", () -> authServer.url("/protocol/openid-connect/certs").toString());
        registry.add("devarom.jwt.issuer-uri", () -> issuerUrl);
        registry.add("devarom.jwt.audience", () -> "devarom-api");
        registry.add("ACCOUNTS_SERVICE_URL", () -> accountsServer.url("").toString().replaceAll("/$", ""));
    }

    private String createTestJwt(String issuer, String audience, boolean expired, RSAKey signingKey) throws Exception {
        Instant now = Instant.now();
        Instant exp = expired ? now.minus(5, ChronoUnit.MINUTES) : now.plus(5, ChronoUnit.MINUTES);

        JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject("customer-uuid-1234")
                .issueTime(Date.from(now.minus(1, ChronoUnit.MINUTES)))
                .expirationTime(Date.from(exp))
                .claim("roles", List.of("CUSTOMER"))
                .claim("preferred_username", "johndoe");

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

    @Test
    @DisplayName("Health endpoint is publicly accessible without authentication")
    void healthEndpoint_IsPubliclyAccessibleWithoutAuth() {
        webTestClient.get()
                .uri("/actuator/health")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("UP");
    }

    @Test
    @DisplayName("Missing bearer token on protected route returns 401 with RFC 7807 Problem Details")
    void missingBearerToken_Returns401WithProblemDetails() {
        webTestClient.get()
                .uri("/api/v1/users/me")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().contentType(MediaType.valueOf("application/problem+json"))
                .expectBody()
                .jsonPath("$.type").isEqualTo("about:blank")
                .jsonPath("$.title").isEqualTo("Unauthorized")
                .jsonPath("$.status").isEqualTo(401)
                .jsonPath("$.detail").isEqualTo("Bearer token is missing, expired, or invalid.")
                .jsonPath("$.instance").isEqualTo("/api/v1/users/me");
    }

    @Test
    @DisplayName("Malformed JWT bearer token returns 401 Unauthorized")
    void malformedJwt_Returns401() {
        webTestClient.get()
                .uri("/api/v1/users/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer not.a.valid.token")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().contentType(MediaType.valueOf("application/problem+json"))
                .expectBody()
                .jsonPath("$.status").isEqualTo(401);
    }

    @Test
    @DisplayName("JWT signed by untrusted key returns 401 Unauthorized")
    void untrustedSignatureJwt_Returns401() throws Exception {
        String token = createTestJwt(issuerUrl, "devarom-api", false, untrustedRsaKey);

        webTestClient.get()
                .uri("/api/v1/users/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.status").isEqualTo(401);
    }

    @Test
    @DisplayName("JWT with wrong issuer returns 401 Unauthorized")
    void wrongIssuerJwt_Returns401() throws Exception {
        String token = createTestJwt("http://attacker.com/realms/evil", "devarom-api", false, trustedRsaKey);

        webTestClient.get()
                .uri("/api/v1/users/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.status").isEqualTo(401);
    }

    @Test
    @DisplayName("JWT with wrong audience returns 401 Unauthorized")
    void wrongAudienceJwt_Returns401() throws Exception {
        String token = createTestJwt(issuerUrl, "unauthorized-client-api", false, trustedRsaKey);

        webTestClient.get()
                .uri("/api/v1/users/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.status").isEqualTo(401);
    }

    @Test
    @DisplayName("Expired JWT returns 401 Unauthorized")
    void expiredJwt_Returns401() throws Exception {
        String token = createTestJwt(issuerUrl, "devarom-api", true, trustedRsaKey);

        webTestClient.get()
                .uri("/api/v1/users/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.status").isEqualTo(401);
    }

    @Test
    @DisplayName("Valid JWT forwards request to Accounts Service and preserves Authorization header")
    void validJwt_RoutesToAccountsServiceAndPreservesAuthHeader() throws Exception {
        String token = createTestJwt(issuerUrl, "devarom-api", false, trustedRsaKey);

        webTestClient.get()
                .uri("/api/v1/users/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.id").isEqualTo("7c9e6679-7425-40de-944b-e07fc1f90ae7")
                .jsonPath("$.email").isEqualTo("customer@example.com")
                .jsonPath("$.roles[0]").isEqualTo("CUSTOMER");

        RecordedRequest recordedRequest = accountsServer.takeRequest();
        assertThat(recordedRequest.getMethod()).isEqualTo("GET");
        assertThat(recordedRequest.getPath()).isEqualTo("/api/v1/users/me");
        assertThat(recordedRequest.getHeader("Authorization")).isEqualTo("Bearer " + token);
    }

    @Test
    @DisplayName("Client-supplied spoofed identity headers are stripped before forwarding")
    void clientSpoofedHeaders_AreStrippedBeforeForwarding() throws Exception {
        String token = createTestJwt(issuerUrl, "devarom-api", false, trustedRsaKey);

        webTestClient.get()
                .uri("/api/v1/users/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header("X-User-Id", "spoofed-user-id")
                .header("X-Roles", "ADMIN")
                .header("X-Tenant-Id", "spoofed-tenant-id")
                .header("X-Keycloak-Sub", "spoofed-sub-id")
                .header("X-User-Email", "attacker@evil.com")
                .exchange()
                .expectStatus().isOk();

        RecordedRequest recordedRequest = accountsServer.takeRequest();
        assertThat(recordedRequest.getHeader("X-User-Id")).isNull();
        assertThat(recordedRequest.getHeader("X-Roles")).isNull();
        assertThat(recordedRequest.getHeader("X-Tenant-Id")).isNull();
        assertThat(recordedRequest.getHeader("X-Keycloak-Sub")).isNull();
        assertThat(recordedRequest.getHeader("X-User-Email")).isNull();
        assertThat(recordedRequest.getHeader("Authorization")).isEqualTo("Bearer " + token);
    }

    @Test
    @DisplayName("CORS preflight from approved frontend origin returns permissive CORS headers")
    void corsPreflight_ApprovedOrigin_ReturnsPermissiveCorsHeaders() {
        webTestClient.options()
                .uri("/api/v1/users/me")
                .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization,Content-Type")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173")
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true")
                .expectHeader().exists(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS);
    }

    @Test
    @DisplayName("CORS preflight from disallowed origin does not return permissive origin header")
    void corsPreflight_DisallowedOrigin_DoesNotReturnPermissiveHeader() {
        webTestClient.options()
                .uri("/api/v1/users/me")
                .header(HttpHeaders.ORIGIN, "http://attacker.evil.com")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization")
                .exchange()
                .expectHeader().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);
    }
}
