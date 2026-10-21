package com.swiftdrop.accountsservice.merchant;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.swiftdrop.accountsservice.identity.CurrentIdentityProvider;
import com.swiftdrop.accountsservice.profile.CurrentUserProfileService;
import com.swiftdrop.accountsservice.security.ProfileSecurityConfiguration;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = {MerchantController.class},
        excludeAutoConfiguration = org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration.class)
@Import({ProfileSecurityConfiguration.class, MerchantProblemHandler.class})
class MerchantHttpSecurityTest {

    private static MockWebServer authServer;
    private static RSAKey trustedRsaKey;
    private static String issuerUrl;

    @Autowired MockMvc mvc;
    @MockitoBean CurrentUserProfileService profiles;
    @MockitoBean MerchantTenantAuthorizationProvider tenantAuthorizationProvider;
    @MockitoBean MerchantApprovalProvider approvalProvider;

    private static final UUID merchantAId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID merchantBId = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String subMerchantA = "keycloak-sub-merchant-a";

    @BeforeAll
    static void setUpServers() throws Exception {
        trustedRsaKey = new RSAKeyGenerator(2048)
                .keyID("trusted-merchant-key")
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
        issuerUrl = authServer.url("/realms/swiftdrop").toString().replaceAll("/$", "");
    }

    @AfterAll
    static void tearDownServers() throws IOException {
        if (authServer != null) {
            authServer.shutdown();
        }
    }

    @DynamicPropertySource
    static void registerDynamicProperties(DynamicPropertyRegistry registry) {
        registry.add("swiftdrop.jwt.jwk-set-uri", () -> authServer.url("/protocol/openid-connect/certs").toString());
        registry.add("swiftdrop.jwt.issuer-uri", () -> issuerUrl);
        registry.add("swiftdrop.jwt.audience", () -> "swiftdrop-api");
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> authServer.url("/protocol/openid-connect/certs").toString());
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> issuerUrl);
    }

    private String createToken(String subject, List<String> roles) throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuerUrl)
                .subject(subject)
                .audience(List.of("swiftdrop-api"))
                .issueTime(Date.from(now.minus(1, ChronoUnit.MINUTES)))
                .expirationTime(Date.from(now.plus(5, ChronoUnit.MINUTES)))
                .claim("roles", roles)
                .claim("name", "Test User")
                .claim("email", "test@swiftdrop.test")
                .build();

        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256)
                .keyID(trustedRsaKey.getKeyID())
                .build();
        SignedJWT signedJWT = new SignedJWT(header, claims);
        signedJWT.sign(new RSASSASigner(trustedRsaKey.toRSAPrivateKey()));
        return signedJWT.serialize();
    }

    @Test
    @DisplayName("1. Unauthenticated request to /api/v1/merchants/me returns 401 Unauthorized")
    void unauthenticatedRequestToMe_returns401() throws Exception {
        mvc.perform(get("/api/v1/merchants/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("status").value(401))
                .andExpect(jsonPath("title").value("Unauthorized"));
    }

    @Test
    @DisplayName("2. Unauthenticated request to /api/v1/merchants/{id} returns 401 Unauthorized")
    void unauthenticatedRequestToId_returns401() throws Exception {
        mvc.perform(get("/api/v1/merchants/{merchantId}", merchantAId))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("status").value(401))
                .andExpect(jsonPath("title").value("Unauthorized"));
    }

    @Test
    @DisplayName("3. CUSTOMER role is denied access to /api/v1/merchants/{id} with 403 Forbidden")
    void customerRoleDeniedMerchantEndpoints_returns403() throws Exception {
        String token = createToken("cust-sub", List.of("CUSTOMER"));

        mvc.perform(get("/api/v1/merchants/{merchantId}", merchantAId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("status").value(403))
                .andExpect(jsonPath("title").value("Forbidden"));
    }

    @Test
    @DisplayName("4. Valid MERCHANT token can access own merchant tenant /api/v1/merchants/me")
    void validMerchantAccessesOwnProfile_returns200() throws Exception {
        String token = createToken(subMerchantA, List.of("MERCHANT"));
        var record = new MerchantRecord(
                merchantAId, UUID.randomUUID(), subMerchantA,
                "Store Direct LK", "PV-123456", MerchantStatus.APPROVED, Instant.now()
        );
        when(tenantAuthorizationProvider.resolveMerchantForIdentity(any())).thenReturn(Optional.of(record));

        mvc.perform(get("/api/v1/merchants/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("id").value(merchantAId.toString()))
                .andExpect(jsonPath("businessName").value("Store Direct LK"))
                .andExpect(jsonPath("status").value("APPROVED"));
    }

    @Test
    @DisplayName("5. Valid MERCHANT token can access own tenant by ID")
    void validMerchantAccessesOwnTenantById_returns200() throws Exception {
        String token = createToken(subMerchantA, List.of("MERCHANT"));
        var record = new MerchantRecord(
                merchantAId, UUID.randomUUID(), subMerchantA,
                "Store Direct LK", "PV-123456", MerchantStatus.APPROVED, Instant.now()
        );
        doNothing().when(tenantAuthorizationProvider).requireTenantAccess(any(), eq(merchantAId));
        when(tenantAuthorizationProvider.resolveMerchantById(merchantAId)).thenReturn(Optional.of(record));

        mvc.perform(get("/api/v1/merchants/{merchantId}", merchantAId).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("id").value(merchantAId.toString()));
    }

    @Test
    @DisplayName("6. Cross-tenant access probe is rejected with 403 Forbidden")
    void crossTenantProbe_returns403() throws Exception {
        String token = createToken(subMerchantA, List.of("MERCHANT"));
        doThrow(new TenantAccessDeniedException("Cross-tenant access denied: Caller does not own merchant tenant " + merchantBId))
                .when(tenantAuthorizationProvider).requireTenantAccess(any(), eq(merchantBId));

        mvc.perform(get("/api/v1/merchants/{merchantId}", merchantBId).header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("status").value(403))
                .andExpect(jsonPath("detail").value("Cross-tenant access denied: Caller does not own merchant tenant " + merchantBId));
    }

    @Test
    @DisplayName("7. ADMIN role is permitted access to merchant tenant details for audit/approval")
    void adminAccessPermitted_returns200() throws Exception {
        String token = createToken("admin-sub", List.of("ADMIN"));
        var record = new MerchantRecord(
                merchantAId, UUID.randomUUID(), subMerchantA,
                "Store Direct LK", "PV-123456", MerchantStatus.APPROVED, Instant.now()
        );
        doNothing().when(tenantAuthorizationProvider).requireTenantAccess(any(), eq(merchantAId));
        when(tenantAuthorizationProvider.resolveMerchantById(merchantAId)).thenReturn(Optional.of(record));

        mvc.perform(get("/api/v1/merchants/{merchantId}", merchantAId).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("id").value(merchantAId.toString()));
    }

    @Test
    @DisplayName("8. Valid MERCHANT token can read approval status via /status")
    void merchantReadsApprovalStatus_returns200() throws Exception {
        String token = createToken(subMerchantA, List.of("MERCHANT"));
        doNothing().when(tenantAuthorizationProvider).requireTenantAccess(any(), eq(merchantAId));
        when(approvalProvider.getApprovalStatus(merchantAId)).thenReturn(Optional.of(MerchantStatus.APPROVED));

        mvc.perform(get("/api/v1/merchants/{merchantId}/status", merchantAId).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("merchantId").value(merchantAId.toString()))
                .andExpect(jsonPath("status").value("APPROVED"))
                .andExpect(jsonPath("approved").value(true));
    }

    @Test
    @DisplayName("9. Missing merchant ID returns 404 Not Found")
    void missingMerchantId_returns404() throws Exception {
        String token = createToken(subMerchantA, List.of("MERCHANT"));
        UUID missingId = UUID.randomUUID();
        doThrow(new MerchantNotFoundException("Merchant not found: " + missingId))
                .when(tenantAuthorizationProvider).requireTenantAccess(any(), eq(missingId));

        mvc.perform(get("/api/v1/merchants/{merchantId}", missingId).header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("status").value(404))
                .andExpect(jsonPath("detail").value("Merchant not found: " + missingId));
    }
}
