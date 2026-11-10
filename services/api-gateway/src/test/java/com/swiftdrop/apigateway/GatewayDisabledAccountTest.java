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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** A disabled user's still-valid token is rejected at the gateway; Accounts outages fail open. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(GatewayDisabledAccountTest.Routes.class)
class GatewayDisabledAccountTest {

    static MockWebServer authServer;
    static MockWebServer backend;
    static MockWebServer accounts;
    static RSAKey key;
    static String issuer;
    static final Map<String, AtomicInteger> accessChecks = new ConcurrentHashMap<>();
    static final Map<String, String> authHeaders = new ConcurrentHashMap<>();

    @LocalServerPort
    int port;

    @TestConfiguration
    static class Routes {
        @Bean
        RouteLocator disabledAccountRoutes(RouteLocatorBuilder builder) {
            return builder.routes()
                    .route("disabled-test", r -> r.path("/api/v1/parcels/**", "/api/v1/tracking/**").uri(backend.url("/").toString()))
                    .build();
        }
    }

    @BeforeAll
    static void start() throws Exception {
        key = new RSAKeyGenerator(2048).keyID("k1").generate();
        authServer = new MockWebServer();
        authServer.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return new MockResponse().setHeader("Content-Type", "application/json").setBody(new JWKSet(key.toPublicJWK()).toString());
            }
        });
        authServer.start();
        issuer = authServer.url("/realms/swiftdrop").toString().replaceAll("/$", "");

        backend = new MockWebServer();
        backend.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return new MockResponse().setHeader("Content-Type", "application/json").setBody("{\"ok\":true}");
            }
        });
        backend.start();

        accounts = new MockWebServer();
        accounts.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String auth = String.valueOf(request.getHeader(HttpHeaders.AUTHORIZATION));
                String subject = subjectOf(auth);
                accessChecks.computeIfAbsent(subject, s -> new AtomicInteger()).incrementAndGet();
                authHeaders.put(subject, auth);
                if (!"/api/v1/users/me/access".equals(request.getPath())) return new MockResponse().setResponseCode(404);
                if (subject.startsWith("broken")) return new MockResponse().setResponseCode(503);
                return new MockResponse().setHeader("Content-Type", "application/json")
                        .setBody("{\"disabled\":" + subject.startsWith("disabled") + "}");
            }
        });
        accounts.start();
    }

    @AfterAll
    static void stop() throws IOException {
        authServer.shutdown();
        backend.shutdown();
        accounts.shutdown();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("swiftdrop.jwt.jwk-set-uri", () -> authServer.url("/certs").toString());
        registry.add("swiftdrop.jwt.issuer-uri", () -> issuer);
        registry.add("swiftdrop.jwt.audience", () -> "swiftdrop-api");
        registry.add("ACCOUNTS_SERVICE_URL", () -> accounts.url("/").toString().replaceAll("/$", ""));
    }

    static String subjectOf(String authorization) {
        try {
            return SignedJWT.parse(authorization.substring("Bearer ".length())).getJWTClaimsSet().getSubject();
        } catch (Exception e) {
            return "unparseable";
        }
    }

    static String token(String subject) throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder().issuer(issuer).subject(subject)
                .issueTime(Date.from(now.minus(1, ChronoUnit.MINUTES))).expirationTime(Date.from(now.plus(1, ChronoUnit.HOURS)))
                .audience(List.of("swiftdrop-api")).claim("roles", List.of("CUSTOMER")).build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(key.toRSAPrivateKey()));
        return jwt.serialize();
    }

    WebTestClient client() {
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void disabledUserIsRejectedEvenWithAValidToken() throws Exception {
        client().get().uri("/api/v1/parcels").header(HttpHeaders.AUTHORIZATION, "Bearer " + token("disabled-1"))
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueMatches(HttpHeaders.WWW_AUTHENTICATE, ".*account disabled.*")
                .expectBody().jsonPath("$.title").isEqualTo("Account disabled");
        // The gateway asked Accounts with the caller's own token.
        assertThat(subjectOf(authHeaders.get("disabled-1"))).isEqualTo("disabled-1");
    }

    @Test
    void activeUserPassesAndTheAnswerIsCached() throws Exception {
        String t = token("active-1");
        for (int i = 0; i < 3; i++) {
            client().get().uri("/api/v1/parcels").header(HttpHeaders.AUTHORIZATION, "Bearer " + t)
                    .exchange().expectStatus().isOk();
        }
        assertThat(accessChecks.get("active-1").get()).isEqualTo(1);
    }

    @Test
    void accountsOutageFailsOpen() throws Exception {
        client().get().uri("/api/v1/parcels").header(HttpHeaders.AUTHORIZATION, "Bearer " + token("broken-1"))
                .exchange().expectStatus().isOk();
    }

    @Test
    void anonymousRequestsAreNotChecked() {
        int before = accessChecks.values().stream().mapToInt(AtomicInteger::get).sum();
        client().get().uri("/api/v1/tracking/SWD-20261109-ABCD").exchange().expectStatus().isOk();
        assertThat(accessChecks.values().stream().mapToInt(AtomicInteger::get).sum()).isEqualTo(before);
    }
}
