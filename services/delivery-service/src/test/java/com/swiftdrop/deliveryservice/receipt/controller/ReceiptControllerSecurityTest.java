package com.swiftdrop.deliveryservice.receipt.controller;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.swiftdrop.deliveryservice.receipt.exception.ReceiptAccessDeniedException;
import com.swiftdrop.deliveryservice.receipt.exception.ReceiptNotAvailableException;
import com.swiftdrop.deliveryservice.receipt.exception.ReceiptNotFoundException;
import com.swiftdrop.deliveryservice.receipt.exception.ReceiptResourceLimitException;
import com.swiftdrop.deliveryservice.receipt.service.ReceiptService;
import com.swiftdrop.deliveryservice.receipt.service.RenderedReceipt;
import com.swiftdrop.deliveryservice.security.ReceiptSecurityConfiguration;
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
import org.springframework.http.HttpHeaders;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = ReceiptController.class,
        excludeAutoConfiguration = org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration.class)
@Import(ReceiptSecurityConfiguration.class)
class ReceiptControllerSecurityTest {

    private static MockWebServer authServer;
    private static RSAKey trustedRsaKey;
    private static RSAKey untrustedRsaKey;
    private static String issuerUrl;

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ReceiptService receiptService;

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

    private String createTestToken(String issuer, String audience, String subject,
                                   List<String> roles, Instant issueTime, Instant expTime,
                                   RSAKey signingKey) throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
                .issuer(issuer != null ? issuer : issuerUrl)
                .audience(audience != null ? audience : "swiftdrop-api")
                .subject(subject != null ? subject : "user-" + UUID.randomUUID())
                .issueTime(Date.from(issueTime != null ? issueTime : now))
                .expirationTime(Date.from(expTime != null ? expTime : now.plus(1, ChronoUnit.HOURS)));

        if (roles != null) {
            builder.claim("roles", roles);
        }

        SignedJWT signedJWT = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).build(),
                builder.build()
        );
        signedJWT.sign(new RSASSASigner(signingKey));
        return signedJWT.serialize();
    }

    @Test
    @DisplayName("1. Anonymous request is rejected with 401 ProblemDetail")
    void anonymousRequestRejectedWith401() throws Exception {
        UUID parcelId = UUID.randomUUID();
        mvc.perform(get("/api/v1/parcels/" + parcelId + "/receipt"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("status").value(401))
                .andExpect(jsonPath("title").value("Unauthorized"))
                .andExpect(jsonPath("detail").value("Bearer token is missing, expired, or invalid."));

        verifyNoInteractions(receiptService);
    }

    @Test
    @DisplayName("2. Untrusted signature token is rejected with 401 ProblemDetail")
    void untrustedSignatureRejectedWith401() throws Exception {
        UUID parcelId = UUID.randomUUID();
        String token = createTestToken(issuerUrl, "swiftdrop-api", "user-1", List.of("CUSTOMER"), null, null, untrustedRsaKey);

        mvc.perform(get("/api/v1/parcels/" + parcelId + "/receipt")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("status").value(401));

        verifyNoInteractions(receiptService);
    }

    @Test
    @DisplayName("3. Expired token is rejected with 401 ProblemDetail")
    void expiredTokenRejectedWith401() throws Exception {
        UUID parcelId = UUID.randomUUID();
        Instant past = Instant.now().minus(2, ChronoUnit.HOURS);
        String token = createTestToken(issuerUrl, "swiftdrop-api", "user-1", List.of("CUSTOMER"), past.minus(1, ChronoUnit.HOURS), past, trustedRsaKey);

        mvc.perform(get("/api/v1/parcels/" + parcelId + "/receipt")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("status").value(401));

        verifyNoInteractions(receiptService);
    }

    @Test
    @DisplayName("4. Wrong audience token is rejected with 401 ProblemDetail")
    void wrongAudienceRejectedWith401() throws Exception {
        UUID parcelId = UUID.randomUUID();
        String token = createTestToken(issuerUrl, "wrong-audience", "user-1", List.of("CUSTOMER"), null, null, trustedRsaKey);

        mvc.perform(get("/api/v1/parcels/" + parcelId + "/receipt")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("status").value(401));

        verifyNoInteractions(receiptService);
    }

    @Test
    @DisplayName("5. Authorized caller returns 200 application/pdf with security and cache control headers")
    void authorizedCallerReturns200WithPdfAndHeaders() throws Exception {
        UUID parcelId = UUID.randomUUID();
        String sub = "customer-123";
        String token = createTestToken(issuerUrl, "swiftdrop-api", sub, List.of("CUSTOMER"), null, null, trustedRsaKey);

        byte[] fakePdfBytes = "%PDF-1.4 mock binary pdf bytes".getBytes();
        RenderedReceipt rendered = new RenderedReceipt(fakePdfBytes, "SWD-20261025-A1B2C3", "receipt-SWD-20261025-A1B2C3.pdf");
        when(receiptService.generateReceipt(eq(parcelId), any())).thenReturn(rendered);

        mvc.perform(get("/api/v1/parcels/" + parcelId + "/receipt")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"receipt-SWD-20261025-A1B2C3.pdf\""))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store, no-cache, must-revalidate, max-age=0"))
                .andExpect(header().string(HttpHeaders.PRAGMA, "no-cache"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(content().bytes(fakePdfBytes));
    }

    @Test
    @DisplayName("6. Unauthorized caller (cross-user IDOR) returns 403 Forbidden ProblemDetail")
    void unauthorizedCallerReturns403ProblemDetail() throws Exception {
        UUID parcelId = UUID.randomUUID();
        String sub = "attacker-sub";
        String token = createTestToken(issuerUrl, "swiftdrop-api", sub, List.of("CUSTOMER"), null, null, trustedRsaKey);

        when(receiptService.generateReceipt(eq(parcelId), any()))
                .thenThrow(new ReceiptAccessDeniedException("Forbidden - unauthorized to download this receipt"));

        mvc.perform(get("/api/v1/parcels/" + parcelId + "/receipt")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("status").value(403))
                .andExpect(jsonPath("title").value("Receipt Access Denied"))
                .andExpect(jsonPath("detail").value("Forbidden - unauthorized to download this receipt"))
                .andExpect(jsonPath("code").value("RECEIPT_FORBIDDEN"));
    }

    @Test
    @DisplayName("7. Nonexistent parcel returns 404 Not Found ProblemDetail")
    void nonexistentParcelReturns404ProblemDetail() throws Exception {
        UUID parcelId = UUID.randomUUID();
        String token = createTestToken(issuerUrl, "swiftdrop-api", "user-1", List.of("CUSTOMER"), null, null, trustedRsaKey);

        when(receiptService.generateReceipt(eq(parcelId), any()))
                .thenThrow(new ReceiptNotFoundException("Receipt not available or parcel not completed"));

        mvc.perform(get("/api/v1/parcels/" + parcelId + "/receipt")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("status").value(404))
                .andExpect(jsonPath("detail").value("Receipt not available or parcel not completed"))
                .andExpect(jsonPath("code").value("RECEIPT_NOT_FOUND"));
    }

    @Test
    @DisplayName("8. Non-completed parcel returns 404 Not Found ProblemDetail per OpenAPI contract")
    void nonCompletedParcelReturns404ProblemDetail() throws Exception {
        UUID parcelId = UUID.randomUUID();
        String token = createTestToken(issuerUrl, "swiftdrop-api", "user-1", List.of("CUSTOMER"), null, null, trustedRsaKey);

        when(receiptService.generateReceipt(eq(parcelId), any()))
                .thenThrow(new ReceiptNotAvailableException("Receipt not available or parcel not completed"));

        mvc.perform(get("/api/v1/parcels/" + parcelId + "/receipt")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("status").value(404))
                .andExpect(jsonPath("detail").value("Receipt not available or parcel not completed"))
                .andExpect(jsonPath("code").value("RECEIPT_UNAVAILABLE"));
    }

    @Test
    @DisplayName("9. Resource limit exceeded returns 422 Unprocessable Content ProblemDetail")
    void resourceLimitExceededReturns422ProblemDetail() throws Exception {
        UUID parcelId = UUID.randomUUID();
        String token = createTestToken(issuerUrl, "swiftdrop-api", "user-1", List.of("CUSTOMER"), null, null, trustedRsaKey);

        when(receiptService.generateReceipt(eq(parcelId), any()))
                .thenThrow(new ReceiptResourceLimitException("Receipt line items exceeded max limit"));

        mvc.perform(get("/api/v1/parcels/" + parcelId + "/receipt")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("status").value(422))
                .andExpect(jsonPath("title").value("Receipt Resource Limit Exceeded"))
                .andExpect(jsonPath("detail").value("Receipt line items exceeded max limit"))
                .andExpect(jsonPath("code").value("RECEIPT_RESOURCE_LIMIT_EXCEEDED"));
    }

    @Test
    @DisplayName("10. Malformed parcel UUID returns 400 Bad Request ProblemDetail")
    void malformedUuidReturns400ProblemDetail() throws Exception {
        String token = createTestToken(issuerUrl, "swiftdrop-api", "user-1", List.of("CUSTOMER"), null, null, trustedRsaKey);

        mvc.perform(get("/api/v1/parcels/not-a-valid-uuid/receipt")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("status").value(400))
                .andExpect(jsonPath("title").value("Bad Request"));

        verifyNoInteractions(receiptService);
    }

    @Test
    @DisplayName("11. Public health endpoint remains accessible without authentication")
    void healthEndpointRemainsPublic() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotIn(401, 403));
    }
}
