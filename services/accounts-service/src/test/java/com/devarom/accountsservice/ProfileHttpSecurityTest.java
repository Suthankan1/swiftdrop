package com.devarom.accountsservice;

import com.devarom.accountsservice.profile.CurrentUserProfile;
import com.devarom.accountsservice.profile.CurrentUserProfileController;
import com.devarom.accountsservice.profile.CurrentUserProfileService;
import com.devarom.accountsservice.security.ProfileSecurityConfiguration;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = CurrentUserProfileController.class,
        excludeAutoConfiguration = org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration.class)
@Import(ProfileSecurityConfiguration.class)
class ProfileHttpSecurityTest {

    private static MockWebServer authServer;
    private static RSAKey trustedRsaKey;
    private static RSAKey untrustedRsaKey;
    private static String issuerUrl;

    @Autowired MockMvc mvc;
    @MockitoBean CurrentUserProfileService profiles;

    @BeforeAll
    static void setUpServers() throws Exception {
        trustedRsaKey = new RSAKeyGenerator(2048)
                .keyID("trusted-test-key")
                .generate();

        untrustedRsaKey = new RSAKeyGenerator(2048)
                .keyID("untrusted-test-key")
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
    }

    @AfterAll
    static void tearDownServers() throws IOException {
        if (authServer != null) {
            authServer.shutdown();
        }
    }

    @DynamicPropertySource
    static void registerDynamicProperties(DynamicPropertyRegistry registry) {
        registry.add("devarom.jwt.jwk-set-uri", () -> authServer.url("/protocol/openid-connect/certs").toString());
        registry.add("devarom.jwt.issuer-uri", () -> issuerUrl);
        registry.add("devarom.jwt.audience", () -> "devarom-api");
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> authServer.url("/protocol/openid-connect/certs").toString());
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> issuerUrl);
    }

    private String createTestToken(String issuer, String audience, String subject,
                                   Object roles, String name, String email,
                                   Instant issueTime, Instant expTime, Instant notBefore,
                                   RSAKey signingKey) throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder();
        if (issuer != null) builder.issuer(issuer);
        if (subject != null) builder.subject(subject);
        if (audience != null) builder.audience(List.of(audience));
        builder.issueTime(Date.from(issueTime != null ? issueTime : now.minus(1, ChronoUnit.MINUTES)));
        builder.expirationTime(Date.from(expTime != null ? expTime : now.plus(5, ChronoUnit.MINUTES)));
        if (notBefore != null) builder.notBeforeTime(Date.from(notBefore));
        if (roles != null) builder.claim("roles", roles);
        if (name != null) builder.claim("name", name);
        if (email != null) builder.claim("email", email);

        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256)
                .keyID(signingKey.getKeyID())
                .build();
        SignedJWT signedJWT = new SignedJWT(header, builder.build());
        signedJWT.sign(new RSASSASigner(signingKey.toRSAPrivateKey()));
        return signedJWT.serialize();
    }

    @Test
    @DisplayName("1. Missing token -> 401")
    void test01_missingToken_returns401() throws Exception {
        mvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("status").value(401))
                .andExpect(jsonPath("title").value("Unauthorized"));
        verifyNoInteractions(profiles);
    }

    @Test
    @DisplayName("2. Malformed token -> 401")
    void test02_malformedToken_returns401() throws Exception {
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer not.a.valid.jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("status").value(401));
        verifyNoInteractions(profiles);
    }

    @Test
    @DisplayName("3. Unsigned token -> 401")
    void test03_unsignedToken_returns401() throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuerUrl)
                .subject("cust-123")
                .audience("devarom-api")
                .expirationTime(Date.from(Instant.now().plus(5, ChronoUnit.MINUTES)))
                .claim("roles", List.of("CUSTOMER"))
                .build();
        PlainJWT plain = new PlainJWT(claims);
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + plain.serialize()))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("status").value(401));
        verifyNoInteractions(profiles);
    }

    @Test
    @DisplayName("4. Untrusted signature -> 401")
    void test04_untrustedSignature_returns401() throws Exception {
        String token = createTestToken(issuerUrl, "devarom-api", "cust-123",
                List.of("CUSTOMER"), "Customer One", "cust@example.test",
                null, null, null, untrustedRsaKey);
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("status").value(401));
        verifyNoInteractions(profiles);
    }

    @Test
    @DisplayName("5. Wrong issuer -> 401")
    void test05_wrongIssuer_returns401() throws Exception {
        String token = createTestToken("http://untrusted-issuer/realms/evil", "devarom-api", "cust-123",
                List.of("CUSTOMER"), "Customer One", "cust@example.test",
                null, null, null, trustedRsaKey);
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("status").value(401));
        verifyNoInteractions(profiles);
    }

    @Test
    @DisplayName("6. Wrong audience -> 401")
    void test06_wrongAudience_returns401() throws Exception {
        String token = createTestToken(issuerUrl, "wrong-api-audience", "cust-123",
                List.of("CUSTOMER"), "Customer One", "cust@example.test",
                null, null, null, trustedRsaKey);
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("status").value(401));
        verifyNoInteractions(profiles);
    }

    @Test
    @DisplayName("7. Expired token -> 401")
    void test07_expiredToken_returns401() throws Exception {
        Instant now = Instant.now();
        String token = createTestToken(issuerUrl, "devarom-api", "cust-123",
                List.of("CUSTOMER"), "Customer One", "cust@example.test",
                now.minus(10, ChronoUnit.MINUTES), now.minus(5, ChronoUnit.MINUTES), null, trustedRsaKey);
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("status").value(401));
        verifyNoInteractions(profiles);
    }

    @Test
    @DisplayName("8. Not-yet-valid token -> 401")
    void test08_notYetValidToken_returns401() throws Exception {
        Instant now = Instant.now();
        String token = createTestToken(issuerUrl, "devarom-api", "cust-123",
                List.of("CUSTOMER"), "Customer One", "cust@example.test",
                now.minus(1, ChronoUnit.MINUTES), now.plus(10, ChronoUnit.MINUTES),
                now.plus(5, ChronoUnit.MINUTES), trustedRsaKey);
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("status").value(401));
        verifyNoInteractions(profiles);
    }

    @Test
    @DisplayName("9. Missing subject -> 401")
    void test09_missingSubject_returns401() throws Exception {
        String token = createTestToken(issuerUrl, "devarom-api", null,
                List.of("CUSTOMER"), "Customer One", "cust@example.test",
                null, null, null, trustedRsaKey);
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("status").value(401));
        verifyNoInteractions(profiles);
    }

    @Test
    @DisplayName("10. Malformed application roles -> safe rejection")
    void test10_malformedApplicationRoles_safeRejection() throws Exception {
        String token = createTestToken(issuerUrl, "devarom-api", "cust-123",
                12345, "Customer One", "cust@example.test",
                null, null, null, trustedRsaKey);
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("status").value(403));
        verifyNoInteractions(profiles);
    }

    @Test
    @DisplayName("11. Missing required application role -> 403")
    void test11_missingRequiredApplicationRole_returns403() throws Exception {
        String token = createTestToken(issuerUrl, "devarom-api", "cust-123",
                List.of("offline_access", "default-roles-devarom"), "Customer One", "cust@example.test",
                null, null, null, trustedRsaKey);
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("status").value(403))
                .andExpect(jsonPath("title").value("Forbidden"));
        verifyNoInteractions(profiles);
    }

    @Test
    @DisplayName("12. Valid CUSTOMER JWT -> authorized profile access")
    void test12_validCustomerJwt_authorizedProfileAccess() throws Exception {
        String sub = "customer-sub-" + UUID.randomUUID();
        CurrentUserProfile expected = new CurrentUserProfile(UUID.randomUUID(), sub,
                "customer@example.test", "Customer One", "", List.of("CUSTOMER"),
                null, Instant.now());
        when(profiles.getOrCreate(any())).thenReturn(expected);

        String token = createTestToken(issuerUrl, "devarom-api", sub,
                List.of("CUSTOMER"), "Customer One", "customer@example.test",
                null, null, null, trustedRsaKey);

        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("keycloakSub").value(sub))
                .andExpect(jsonPath("email").value("customer@example.test"))
                .andExpect(jsonPath("fullName").value("Customer One"))
                .andExpect(jsonPath("roles[0]").value("CUSTOMER"))
                .andExpect(jsonPath("merchantProfile").doesNotExist());

        verify(profiles).getOrCreate(any());
    }

    @Test
    @DisplayName("13. Valid MERCHANT JWT -> correct role mapping")
    void test13_validMerchantJwt_correctRoleMapping() throws Exception {
        String sub = "merchant-sub-" + UUID.randomUUID();
        CurrentUserProfile expected = new CurrentUserProfile(UUID.randomUUID(), sub,
                "merchant@store.test", "Store Admin", "", List.of("MERCHANT"),
                null, Instant.now());
        when(profiles.getOrCreate(any())).thenReturn(expected);

        String token = createTestToken(issuerUrl, "devarom-api", sub,
                List.of("MERCHANT"), "Store Admin", "merchant@store.test",
                null, null, null, trustedRsaKey);

        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("keycloakSub").value(sub))
                .andExpect(jsonPath("roles[0]").value("MERCHANT"));
    }

    @Test
    @DisplayName("14. Unauthorized/unrecognized role does not gain access")
    void test14_unauthorizedRole_doesNotGainAccess() throws Exception {
        String token = createTestToken(issuerUrl, "devarom-api", "sub-unauth",
                List.of("UNKNOWN_STAFF_ROLE"), "Unknown User", "user@test.test",
                null, null, null, trustedRsaKey);
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("status").value(403));
        verifyNoInteractions(profiles);
    }

    @Test
    @DisplayName("15. Spoofed X-User-Id/X-Keycloak-Sub/X-Roles ignored")
    void test15_spoofedHeadersIgnored() throws Exception {
        String actualSub = "genuine-cust-" + UUID.randomUUID();
        CurrentUserProfile expected = new CurrentUserProfile(UUID.randomUUID(), actualSub,
                "cust@example.test", "Genuine", "", List.of("CUSTOMER"),
                null, Instant.now());
        when(profiles.getOrCreate(any())).thenReturn(expected);

        String token = createTestToken(issuerUrl, "devarom-api", actualSub,
                List.of("CUSTOMER"), "Genuine", "cust@example.test",
                null, null, null, trustedRsaKey);

        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + token)
                        .param("subject", "attacker-chosen")
                        .param("id", "attacker-id")
                        .header("X-User-Id", "attacker")
                        .header("X-Keycloak-Sub", "attacker")
                        .header("X-Roles", "ADMIN")
                        .header("X-User-Email", "attacker@evil.test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("keycloakSub").value(actualSub));

        verify(profiles).getOrCreate(org.mockito.ArgumentMatchers.argThat(id ->
                id.subject().equals(actualSub) &&
                !id.roles().contains("ADMIN") &&
                id.roles().contains("CUSTOMER")
        ));
    }

    @Test
    @DisplayName("16. Valid bearer authentication works without Gateway")
    void test16_validBearerWorksDirectlyWithoutGateway() throws Exception {
        String sub = "direct-sub-" + UUID.randomUUID();
        CurrentUserProfile expected = new CurrentUserProfile(UUID.randomUUID(), sub,
                "direct@example.test", "Direct User", "", List.of("CUSTOMER"),
                null, Instant.now());
        when(profiles.getOrCreate(any())).thenReturn(expected);

        String token = createTestToken(issuerUrl, "devarom-api", sub,
                List.of("CUSTOMER"), "Direct User", "direct@example.test",
                null, null, null, trustedRsaKey);

        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("keycloakSub").value(sub));
    }

    @Test
    @DisplayName("17. Public health endpoint remains accessible")
    void test17_publicHealthEndpoint_remainsAccessible() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotIn(401, 403));
    }

    @Test
    @DisplayName("18. Basic/form login remains unavailable")
    void test18_basicAndFormLoginAreUnavailable() throws Exception {
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Basic dGVzdDp0ZXN0"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist("WWW-Authenticate"));
        mvc.perform(get("/login")).andExpect(status().isUnauthorized());
        verifyNoInteractions(profiles);
    }

    @Test
    @DisplayName("19. No unexpected public Actuator endpoints")
    void test19_noUnexpectedPublicActuatorEndpoints() throws Exception {
        mvc.perform(get("/actuator")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("20. Correct ProblemDetail and no-store behavior")
    void test20_correctProblemDetailAndNoStoreBehavior() throws Exception {
        mvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("type").value("about:blank"))
                .andExpect(jsonPath("title").value("Unauthorized"))
                .andExpect(jsonPath("status").value(401))
                .andExpect(jsonPath("detail").value("Bearer token is missing, expired, or invalid."))
                .andExpect(jsonPath("instance").value("/api/v1/users/me"));

        String sub = "sub-" + UUID.randomUUID();
        when(profiles.getOrCreate(any())).thenReturn(new CurrentUserProfile(
                UUID.randomUUID(), sub, "", "", "", List.of("CUSTOMER"), null, Instant.now()));
        String token = createTestToken(issuerUrl, "devarom-api", sub, List.of("CUSTOMER"), "", "", null, null, null, trustedRsaKey);
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"));
    }
}
