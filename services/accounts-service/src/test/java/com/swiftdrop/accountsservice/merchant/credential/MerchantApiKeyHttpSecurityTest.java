package com.swiftdrop.accountsservice.merchant.credential;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.swiftdrop.accountsservice.merchant.MerchantNotApprovedException;
import com.swiftdrop.accountsservice.merchant.MerchantProblemHandler;
import com.swiftdrop.accountsservice.merchant.MerchantStatus;
import com.swiftdrop.accountsservice.merchant.TenantAccessDeniedException;
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
import org.springframework.http.MediaType;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = {MerchantApiKeyController.class},
        excludeAutoConfiguration = org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration.class)
@Import({ProfileSecurityConfiguration.class, MerchantProblemHandler.class})
class MerchantApiKeyHttpSecurityTest {

    private static MockWebServer authServer;
    private static RSAKey trustedRsaKey;
    private static String issuerUrl;

    @Autowired MockMvc mvc;
    @MockitoBean CurrentUserProfileService profiles;
    @MockitoBean MerchantApiKeyService apiKeyService;

    private static final UUID merchantAId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID merchantBId = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID keyId = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final String subMerchantA = "keycloak-sub-merchant-a";

    @BeforeAll
    static void setUpServers() throws Exception {
        trustedRsaKey = new RSAKeyGenerator(2048)
                .keyID("trusted-merchant-api-key")
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
                .claim("name", "Merchant User")
                .claim("email", "merchant@swiftdrop.test")
                .build();

        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256)
                .keyID(trustedRsaKey.getKeyID())
                .build();
        SignedJWT signedJWT = new SignedJWT(header, claims);
        signedJWT.sign(new RSASSASigner(trustedRsaKey.toRSAPrivateKey()));
        return signedJWT.serialize();
    }

    @Test
    @DisplayName("1. Unauthenticated request to POST /api-keys returns 401 Unauthorized")
    void unauthenticatedPost_returns401() throws Exception {
        mvc.perform(post("/api/v1/merchants/{merchantId}/api-keys", merchantAId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Test\",\"scopes\":[\"parcels:create\"]}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("status").value(401))
                .andExpect(jsonPath("title").value("Unauthorized"));
    }

    @Test
    @DisplayName("2. Unauthenticated request to GET /api-keys returns 401 Unauthorized")
    void unauthenticatedGet_returns401() throws Exception {
        mvc.perform(get("/api/v1/merchants/{merchantId}/api-keys", merchantAId))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("status").value(401));
    }

    @Test
    @DisplayName("3. Unauthenticated request to DELETE /api-keys/{id} returns 401 Unauthorized")
    void unauthenticatedDelete_returns401() throws Exception {
        mvc.perform(delete("/api/v1/merchants/{merchantId}/api-keys/{keyId}", merchantAId, keyId))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("status").value(401));
    }

    @Test
    @DisplayName("4. CUSTOMER role is denied POST /api-keys with 403 Forbidden")
    void customerRole_post_returns403() throws Exception {
        String token = createToken("cust-sub", List.of("CUSTOMER"));

        mvc.perform(post("/api/v1/merchants/{merchantId}/api-keys", merchantAId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Test\",\"scopes\":[\"parcels:create\"]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("status").value(403));
    }

    @Test
    @DisplayName("5. CUSTOMER role is denied GET /api-keys with 403 Forbidden")
    void customerRole_get_returns403() throws Exception {
        String token = createToken("cust-sub", List.of("CUSTOMER"));

        mvc.perform(get("/api/v1/merchants/{merchantId}/api-keys", merchantAId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("status").value(403));
    }

    @Test
    @DisplayName("6. Authorized MERCHANT can generate API key, receiving 201 Created with Cache-Control: no-store")
    void authorizedMerchant_generateKey_returns201() throws Exception {
        String token = createToken(subMerchantA, List.of("MERCHANT"));
        Instant now = Instant.now();
        var responseDto = new ApiKeyCreatedResponse(
                keyId, "Order Integration", "swd_live_7a8b", "swd_live_7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d",
                List.of("parcels:create"), now, now.plus(90, ChronoUnit.DAYS)
        );

        when(apiKeyService.generateApiKey(any(), eq(merchantAId), any())).thenReturn(responseDto);

        mvc.perform(post("/api/v1/merchants/{merchantId}/api-keys", merchantAId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Order Integration\",\"scopes\":[\"parcels:create\"],\"expiresInDays\":90}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("keyId").value(keyId.toString()))
                .andExpect(jsonPath("name").value("Order Integration"))
                .andExpect(jsonPath("keyPrefix").value("swd_live_7a8b"))
                .andExpect(jsonPath("plaintextKey").value("swd_live_7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d"))
                .andExpect(jsonPath("scopes[0]").value("parcels:create"));
    }

    @Test
    @DisplayName("7. Authorized MERCHANT can list key metadata, receiving 200 OK with Cache-Control: no-store")
    void authorizedMerchant_listKeys_returns200() throws Exception {
        String token = createToken(subMerchantA, List.of("MERCHANT"));
        Instant now = Instant.now();
        var summary = new ApiKeySummaryItem(
                keyId, "Warehouse Key", "swd_live_7a8b", List.of("parcels:create"),
                now.minus(1, ChronoUnit.DAYS), now.plus(89, ChronoUnit.DAYS), false
        );
        when(apiKeyService.listApiKeys(any(), eq(merchantAId))).thenReturn(new ApiKeyListResponse(List.of(summary)));

        mvc.perform(get("/api/v1/merchants/{merchantId}/api-keys", merchantAId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("keys[0].keyId").value(keyId.toString()))
                .andExpect(jsonPath("keys[0].name").value("Warehouse Key"))
                .andExpect(jsonPath("keys[0].keyPrefix").value("swd_live_7a8b"))
                .andExpect(jsonPath("keys[0].isRevoked").value(false))
                .andExpect(jsonPath("keys[0].plaintextKey").doesNotExist())
                .andExpect(jsonPath("keys[0].keyDigest").doesNotExist());
    }

    @Test
    @DisplayName("8. Authorized MERCHANT can revoke key via DELETE, receiving 204 No Content")
    void authorizedMerchant_revokeKey_returns204() throws Exception {
        String token = createToken(subMerchantA, List.of("MERCHANT"));
        doNothing().when(apiKeyService).revokeApiKey(any(), eq(merchantAId), eq(keyId));

        mvc.perform(delete("/api/v1/merchants/{merchantId}/api-keys/{keyId}", merchantAId, keyId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    @DisplayName("9. Key revocation via POST alias returns 204 No Content")
    void authorizedMerchant_revokeKeyPost_returns204() throws Exception {
        String token = createToken(subMerchantA, List.of("MERCHANT"));
        doNothing().when(apiKeyService).revokeApiKey(any(), eq(merchantAId), eq(keyId));

        mvc.perform(post("/api/v1/merchants/{merchantId}/api-keys/{keyId}/revoke", merchantAId, keyId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    @DisplayName("10. Cross-tenant key creation probe returns 403 Forbidden")
    void crossTenantProbe_returns403() throws Exception {
        String token = createToken(subMerchantA, List.of("MERCHANT"));
        doThrow(new TenantAccessDeniedException("Cross-tenant access denied"))
                .when(apiKeyService).generateApiKey(any(), eq(merchantBId), any());

        mvc.perform(post("/api/v1/merchants/{merchantId}/api-keys", merchantBId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Probe\",\"scopes\":[\"parcels:create\"]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("status").value(403))
                .andExpect(jsonPath("detail").value("Cross-tenant access denied"));
    }

    @Test
    @DisplayName("11. PENDING merchant credential creation attempt returns 403 Forbidden")
    void pendingMerchant_createKey_returns403() throws Exception {
        String token = createToken(subMerchantA, List.of("MERCHANT"));
        doThrow(new MerchantNotApprovedException(MerchantStatus.PENDING, "Merchant operation requires APPROVED status"))
                .when(apiKeyService).generateApiKey(any(), eq(merchantAId), any());

        mvc.perform(post("/api/v1/merchants/{merchantId}/api-keys", merchantAId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Test\",\"scopes\":[\"parcels:create\"]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("status").value(403))
                .andExpect(jsonPath("detail").value("Merchant operation requires APPROVED status"));
    }

    @Test
    @DisplayName("12. Active key limit exceeded returns 409 Conflict")
    void keyLimitExceeded_returns409() throws Exception {
        String token = createToken(subMerchantA, List.of("MERCHANT"));
        doThrow(new ApiKeyLimitExceededException("Merchant tenant has reached maximum active API key limit of 3"))
                .when(apiKeyService).generateApiKey(any(), eq(merchantAId), any());

        mvc.perform(post("/api/v1/merchants/{merchantId}/api-keys", merchantAId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Test\",\"scopes\":[\"parcels:create\"]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("status").value(409))
                .andExpect(jsonPath("detail").value("Merchant tenant has reached maximum active API key limit of 3"));
    }

    @Test
    @DisplayName("13. Revoking unknown key returns 404 Not Found")
    void unknownKeyRevocation_returns404() throws Exception {
        String token = createToken(subMerchantA, List.of("MERCHANT"));
        UUID missingKeyId = UUID.randomUUID();
        doThrow(new ApiKeyNotFoundException("API key not found: " + missingKeyId))
                .when(apiKeyService).revokeApiKey(any(), eq(merchantAId), eq(missingKeyId));

        mvc.perform(delete("/api/v1/merchants/{merchantId}/api-keys/{keyId}", merchantAId, missingKeyId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("status").value(404));
    }

    @Test
    @DisplayName("14. Invalid scope returns 400 Bad Request")
    void invalidScope_returns400() throws Exception {
        String token = createToken(subMerchantA, List.of("MERCHANT"));
        doThrow(new InvalidApiKeyScopeException("Invalid scope: 'superadmin'. Allowed scopes: [parcels:create, parcels:read, webhooks:manage]"))
                .when(apiKeyService).generateApiKey(any(), eq(merchantAId), any());

        mvc.perform(post("/api/v1/merchants/{merchantId}/api-keys", merchantAId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Test\",\"scopes\":[\"superadmin\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("status").value(400))
                .andExpect(jsonPath("detail").value(org.hamcrest.Matchers.containsString("superadmin")));
    }
}
