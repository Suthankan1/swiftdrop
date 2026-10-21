package com.swiftdrop.accountsservice;

import com.swiftdrop.accountsservice.profile.CurrentUserProfile;
import com.swiftdrop.accountsservice.profile.CurrentUserProfileController;
import com.swiftdrop.accountsservice.profile.CurrentUserProfileService;
import com.swiftdrop.accountsservice.security.ProfileSecurityConfiguration;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Validates that Accounts Service independently enforces the target SwiftDrop contract:
 *   - Issuer: http://localhost:8090/realms/swiftdrop
 *   - Audience: swiftdrop-api
 *   - Preservation of existing profile UUID and subject continuity
 *   - Strict rejection of legacy devarom issuer and devarom-api audience tokens
 */
@WebMvcTest(controllers = CurrentUserProfileController.class,
        excludeAutoConfiguration = org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration.class)
@Import(ProfileSecurityConfiguration.class)
class SwiftDropTargetContractAccountsTest {

    private static MockWebServer authServer;
    private static RSAKey trustedRsaKey;
    private static RSAKey untrustedRsaKey;
    private static String swiftDropIssuerUrl;
    private static String legacyIssuerUrl;

    @Autowired MockMvc mvc;
    @MockitoBean CurrentUserProfileService profiles;

    @BeforeAll
    static void setUpServers() throws Exception {
        trustedRsaKey = new RSAKeyGenerator(2048)
                .keyID("swiftdrop-accounts-key")
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
    }

    @AfterAll
    static void tearDownServers() throws IOException {
        if (authServer != null) {
            authServer.shutdown();
        }
    }

    @DynamicPropertySource
    static void registerDynamicProperties(DynamicPropertyRegistry registry) {
        // Target SwiftDrop configuration injected via standard environment variable keys
        registry.add("KEYCLOAK_JWKS_URI", () -> authServer.url("/realms/swiftdrop/protocol/openid-connect/certs").toString());
        registry.add("KEYCLOAK_ISSUER_URI", () -> swiftDropIssuerUrl);
        registry.add("JWT_AUDIENCE", () -> "swiftdrop-api");
    }

    private String createToken(String issuer, String audience, String subject,
                               Object roles, String name, String email,
                               Instant issueTime, Instant expTime, RSAKey signingKey) throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder();
        if (issuer != null) builder.issuer(issuer);
        if (subject != null) builder.subject(subject);
        if (audience != null) builder.audience(List.of(audience));
        builder.issueTime(Date.from(issueTime != null ? issueTime : now.minus(1, ChronoUnit.MINUTES)));
        builder.expirationTime(Date.from(expTime != null ? expTime : now.plus(5, ChronoUnit.MINUTES)));
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
    @DisplayName("1. Target SwiftDrop JWT resolves pre-existing profile by keycloak_sub")
    void targetSwiftDropToken_ResolvesExistingProfileContinuity() throws Exception {
        String existingSub = "swiftdrop-existing-sub-" + UUID.randomUUID();
        UUID existingProfileId = UUID.randomUUID();
        CurrentUserProfile existingProfile = new CurrentUserProfile(
                existingProfileId, existingSub, "alice@swiftdrop.test", "Alice User",
                "+94771234567", List.of("CUSTOMER"), null, Instant.now());

        when(profiles.getOrCreate(any())).thenReturn(existingProfile);

        String token = createToken(swiftDropIssuerUrl, "swiftdrop-api", existingSub,
                List.of("CUSTOMER"), "Alice User", "alice@swiftdrop.test", null, null, trustedRsaKey);

        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("id").value(existingProfileId.toString()))
                .andExpect(jsonPath("keycloakSub").value(existingSub))
                .andExpect(jsonPath("email").value("alice@swiftdrop.test"))
                .andExpect(jsonPath("roles[0]").value("CUSTOMER"));

        verify(profiles).getOrCreate(org.mockito.ArgumentMatchers.argThat(identity ->
                identity.subject().equals(existingSub) &&
                identity.roles().contains("CUSTOMER")
        ));
    }

    @Test
    @DisplayName("2. Legacy devarom issuer token is strictly rejected under SwiftDrop configuration")
    void legacyIssuerToken_RejectedWith401() throws Exception {
        String sub = "legacy-sub-" + UUID.randomUUID();
        String token = createToken(legacyIssuerUrl, "swiftdrop-api", sub,
                List.of("CUSTOMER"), "Legacy User", "legacy@devarom.test", null, null, trustedRsaKey);

        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("status").value(401))
                .andExpect(jsonPath("title").value("Unauthorized"));

        verifyNoInteractions(profiles);
    }

    @Test
    @DisplayName("3. Legacy devarom-api audience token is strictly rejected under SwiftDrop configuration")
    void legacyAudienceToken_RejectedWith401() throws Exception {
        String sub = "legacy-aud-sub-" + UUID.randomUUID();
        String token = createToken(swiftDropIssuerUrl, "devarom-api", sub,
                List.of("CUSTOMER"), "Legacy Aud User", "user@swiftdrop.test", null, null, trustedRsaKey);

        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("status").value(401));

        verifyNoInteractions(profiles);
    }

    @Test
    @DisplayName("4. Untrusted signing key is strictly rejected with 401")
    void untrustedSignatureToken_RejectedWith401() throws Exception {
        String token = createToken(swiftDropIssuerUrl, "swiftdrop-api", "sub-untrusted",
                List.of("CUSTOMER"), "Untrusted", "untrusted@swiftdrop.test", null, null, untrustedRsaKey);

        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("status").value(401));

        verifyNoInteractions(profiles);
    }

    @Test
    @DisplayName("5. Expired SwiftDrop token is rejected with 401")
    void expiredSwiftDropToken_RejectedWith401() throws Exception {
        Instant now = Instant.now();
        String token = createToken(swiftDropIssuerUrl, "swiftdrop-api", "sub-expired",
                List.of("CUSTOMER"), "Expired", "expired@swiftdrop.test",
                now.minus(10, ChronoUnit.MINUTES), now.minus(5, ChronoUnit.MINUTES), trustedRsaKey);

        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("status").value(401));

        verifyNoInteractions(profiles);
    }

    @Test
    @DisplayName("6. SwiftDrop token with unapproved role is rejected with 403 Forbidden")
    void unapprovedRoleToken_RejectedWith403() throws Exception {
        String token = createToken(swiftDropIssuerUrl, "swiftdrop-api", "sub-norole",
                List.of("UNKNOWN_ROLE"), "No Role User", "norole@swiftdrop.test", null, null, trustedRsaKey);

        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("status").value(403))
                .andExpect(jsonPath("title").value("Forbidden"));

        verifyNoInteractions(profiles);
    }

    @Test
    @DisplayName("7. Spoofed headers and query parameters are ignored during profile resolution")
    void spoofedHeadersIgnored_SecurityContextAuthoritative() throws Exception {
        String genuineSub = "genuine-sub-" + UUID.randomUUID();
        CurrentUserProfile profile = new CurrentUserProfile(
                UUID.randomUUID(), genuineSub, "genuine@swiftdrop.test", "Genuine User",
                "", List.of("CUSTOMER"), null, Instant.now());
        when(profiles.getOrCreate(any())).thenReturn(profile);

        String token = createToken(swiftDropIssuerUrl, "swiftdrop-api", genuineSub,
                List.of("CUSTOMER"), "Genuine User", "genuine@swiftdrop.test", null, null, trustedRsaKey);

        mvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer " + token)
                        .header("X-User-Id", "attacker-sub")
                        .header("X-Keycloak-Sub", "attacker-sub")
                        .header("X-Roles", "ADMIN")
                        .param("subject", "attacker-sub"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("keycloakSub").value(genuineSub));

        verify(profiles).getOrCreate(org.mockito.ArgumentMatchers.argThat(id ->
                id.subject().equals(genuineSub) &&
                !id.roles().contains("ADMIN")
        ));
    }
}
