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

/**
 * Validates that Spring Cloud Gateway enforces the target SwiftDrop contract:
 *   - Issuer: http://localhost:8090/realms/swiftdrop
 *   - Audience: swiftdrop-api
 *   - Rejection of legacy devarom issuer and devarom-api audience tokens
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class SwiftDropTargetContractGatewayTest {

    private static MockWebServer authServer;
    private static MockWebServer accountsServer;

    private static RSAKey trustedRsaKey;
    private static RSAKey untrustedRsaKey;
    private static String swiftDropIssuerUrl;
    private static String legacyIssuerUrl;

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
                .keyID("swiftdrop-trusted-key")
                .generate();

        untrustedRsaKey = new RSAKeyGenerator(2048)
                .keyID("swiftdrop-untrusted-key")
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
        swiftDropIssuerUrl = authServer.url("/realms/swiftdrop").toString().replaceAll("/$", "");
        legacyIssuerUrl = authServer.url("/realms/devarom").toString().replaceAll("/$", "");

        accountsServer = new MockWebServer();
        accountsServer.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                if ("/api/v1/users/me".equals(request.getPath())) {
                    return new MockResponse()
                            .setResponseCode(200)
                            .setHeader("Content-Type", "application/json")
                            .setBody("{\"id\":\"11111111-2222-3333-4444-555555555555\",\"keycloakSub\":\"swiftdrop-sub-001\",\"email\":\"alice@swiftdrop.test\",\"roles\":[\"CUSTOMER\"]}");
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
        // Explicitly set target SwiftDrop configuration via environment variable overrides
        registry.add("KEYCLOAK_JWKS_URI", () -> authServer.url("/realms/swiftdrop/protocol/openid-connect/certs").toString());
        registry.add("KEYCLOAK_ISSUER_URI", () -> swiftDropIssuerUrl);
        registry.add("JWT_AUDIENCE", () -> "swiftdrop-api");
        registry.add("ACCOUNTS_SERVICE_URL", () -> accountsServer.url("").toString().replaceAll("/$", ""));
    }

    private String createToken(String issuer, String audience, boolean expired, RSAKey signingKey) throws Exception {
        Instant now = Instant.now();
        Instant exp = expired ? now.minus(5, ChronoUnit.MINUTES) : now.plus(5, ChronoUnit.MINUTES);

        JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject("swiftdrop-customer-uuid")
                .issueTime(Date.from(now.minus(1, ChronoUnit.MINUTES)))
                .expirationTime(Date.from(exp))
                .claim("roles", List.of("CUSTOMER"))
                .claim("preferred_username", "swiftdrop_user");

        if (audience != null) {
            builder.audience(List.of(audience));
        }

        SignedJWT signedJWT = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).build(),
                builder.build()
        );
        signedJWT.sign(new RSASSASigner(signingKey.toRSAPrivateKey()));
        return signedJWT.serialize();
    }

    @Test
    @DisplayName("1. Target SwiftDrop JWT (swiftdrop issuer + swiftdrop-api audience) is accepted and routed")
    void targetSwiftDropJwt_AcceptedAndRouted() throws Exception {
        String token = createToken(swiftDropIssuerUrl, "swiftdrop-api", false, trustedRsaKey);

        webTestClient.get()
                .uri("/api/v1/users/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.keycloakSub").isEqualTo("swiftdrop-sub-001")
                .jsonPath("$.roles[0]").isEqualTo("CUSTOMER");

        RecordedRequest recorded = accountsServer.takeRequest();
        assertThat(recorded.getPath()).isEqualTo("/api/v1/users/me");
        assertThat(recorded.getHeader("Authorization")).isEqualTo("Bearer " + token);
    }

    @Test
    @DisplayName("2. Legacy devarom issuer token is strictly rejected under SwiftDrop configuration")
    void legacyIssuerToken_StrictlyRejectedWith401() throws Exception {
        String token = createToken(legacyIssuerUrl, "swiftdrop-api", false, trustedRsaKey);

        webTestClient.get()
                .uri("/api/v1/users/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().contentType(MediaType.valueOf("application/problem+json"))
                .expectBody()
                .jsonPath("$.status").isEqualTo(401);
    }

    @Test
    @DisplayName("3. Legacy devarom-api audience token is strictly rejected under SwiftDrop configuration")
    void legacyAudienceToken_StrictlyRejectedWith401() throws Exception {
        String token = createToken(swiftDropIssuerUrl, "devarom-api", false, trustedRsaKey);

        webTestClient.get()
                .uri("/api/v1/users/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().contentType(MediaType.valueOf("application/problem+json"))
                .expectBody()
                .jsonPath("$.status").isEqualTo(401);
    }

    @Test
    @DisplayName("4. Untrusted signature token is strictly rejected with 401")
    void untrustedSignatureToken_RejectedWith401() throws Exception {
        String token = createToken(swiftDropIssuerUrl, "swiftdrop-api", false, untrustedRsaKey);

        webTestClient.get()
                .uri("/api/v1/users/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.status").isEqualTo(401);
    }

    @Test
    @DisplayName("5. Expired SwiftDrop token is rejected with 401")
    void expiredSwiftDropToken_RejectedWith401() throws Exception {
        String token = createToken(swiftDropIssuerUrl, "swiftdrop-api", true, trustedRsaKey);

        webTestClient.get()
                .uri("/api/v1/users/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.status").isEqualTo(401);
    }

    @Test
    @DisplayName("6. Gateway strips spoofed headers when routing target SwiftDrop requests")
    void spoofedHeadersStrippedOnSwiftDropRoute() throws Exception {
        String token = createToken(swiftDropIssuerUrl, "swiftdrop-api", false, trustedRsaKey);

        webTestClient.get()
                .uri("/api/v1/users/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header("X-User-Id", "attacker-uuid")
                .header("X-Roles", "ADMIN")
                .header("X-Keycloak-Sub", "attacker-sub")
                .exchange()
                .expectStatus().isOk();

        RecordedRequest recorded = accountsServer.takeRequest();
        assertThat(recorded.getHeader("X-User-Id")).isNull();
        assertThat(recorded.getHeader("X-Roles")).isNull();
        assertThat(recorded.getHeader("X-Keycloak-Sub")).isNull();
        assertThat(recorded.getHeader("Authorization")).isEqualTo("Bearer " + token);
    }
}
