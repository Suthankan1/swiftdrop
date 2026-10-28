package com.swiftdrop.deliveryservice.query.controller;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.swiftdrop.deliveryservice.core.ParcelNotFoundException;
import com.swiftdrop.deliveryservice.core.ParcelStatus;
import com.swiftdrop.deliveryservice.query.dto.PagedResponse;
import com.swiftdrop.deliveryservice.query.dto.ParcelDetailResponse;
import com.swiftdrop.deliveryservice.query.dto.ParcelStatusHistoryResponse;
import com.swiftdrop.deliveryservice.query.exception.ParcelAccessDeniedException;
import com.swiftdrop.deliveryservice.query.service.DefaultParcelQueryService;
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
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = ParcelQueryController.class,
        excludeAutoConfiguration = org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration.class)
@Import({ReceiptSecurityConfiguration.class, ParcelQueryProblemHandler.class})
class ParcelQueryControllerSecurityTest {

    private static MockWebServer authServer;
    private static RSAKey trustedRsaKey;
    private static RSAKey untrustedRsaKey;
    private static String issuerUrl;

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private DefaultParcelQueryService queryService;

    private static final UUID PARCEL_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID MERCHANT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String SENDER_SUB = "cust-alice-sub";
    private static final String COURIER_SUB = "courier-charlie-sub";

    @BeforeAll
    static void setUpServers() throws Exception {
        trustedRsaKey = new RSAKeyGenerator(2048)
                .keyID("trusted-query-key")
                .generate();

        untrustedRsaKey = new RSAKeyGenerator(2048)
                .keyID("untrusted-query-key")
                .generate();

        authServer = new MockWebServer();
        authServer.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                if (request.getPath() != null && request.getPath().contains("/protocol/openid-connect/certs")) {
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
    static void registerSecurityProperties(DynamicPropertyRegistry registry) {
        registry.add("swiftdrop.jwt.jwk-set-uri", () -> authServer.url("/protocol/openid-connect/certs").toString());
        registry.add("swiftdrop.jwt.issuer-uri", () -> issuerUrl);
        registry.add("swiftdrop.jwt.audience", () -> "swiftdrop-api");
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> authServer.url("/protocol/openid-connect/certs").toString());
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> issuerUrl);
        registry.add("KEYCLOAK_JWKS_URI", () -> authServer.url("/protocol/openid-connect/certs").toString());
        registry.add("KEYCLOAK_ISSUER_URI", () -> issuerUrl);
        registry.add("JWT_AUDIENCE", () -> "swiftdrop-api");
    }

    private String issueToken(String subject, List<String> roles, Map<String, Object> extraClaims, RSAKey signingKey, String audience, Instant expiry) throws Exception {
        JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
                .subject(subject)
                .issuer(issuerUrl)
                .audience(audience != null ? audience : "swiftdrop-api")
                .issueTime(new Date())
                .expirationTime(Date.from(expiry != null ? expiry : Instant.now().plus(1, ChronoUnit.HOURS)));

        if (roles != null) {
            builder.claim("roles", roles);
        }
        if (extraClaims != null) {
            extraClaims.forEach(builder::claim);
        }

        SignedJWT signedJWT = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).build(),
                builder.build()
        );
        signedJWT.sign(new RSASSASigner(signingKey));
        return signedJWT.serialize();
    }

    private ParcelDetailResponse sampleDetailResponse() {
        return new ParcelDetailResponse(
                PARCEL_ID,
                "SWD-20261025-A1B2C3",
                SENDER_SUB,
                MERCHANT_ID,
                ParcelStatus.IN_TRANSIT,
                3L,
                2.50,
                "STANDARD",
                350000L,
                "LKR",
                "RATE-2026-V1",
                "",
                "",
                COURIER_SUB,
                Instant.now().minusSeconds(1800)
        );
    }

    // --- Authentication Invariant Tests ---

    @Test
    @DisplayName("Unauthenticated request returns 401 ProblemDetail")
    void unauthenticatedReturns401() throws Exception {
        mvc.perform(get("/api/v1/parcels/" + PARCEL_ID)
                        .accept(MediaType.APPLICATION_JSON, MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.title").value("Unauthorized"));

        verifyNoInteractions(queryService);
    }

    @Test
    @DisplayName("Invalid token signed by untrusted key returns 401")
    void untrustedTokenReturns401() throws Exception {
        String token = issueToken(SENDER_SUB, List.of("CUSTOMER"), null, untrustedRsaKey, "swiftdrop-api", null);

        mvc.perform(get("/api/v1/parcels/" + PARCEL_ID)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401));

        verifyNoInteractions(queryService);
    }

    @Test
    @DisplayName("Token with wrong audience returns 401")
    void wrongAudienceReturns401() throws Exception {
        String token = issueToken(SENDER_SUB, List.of("CUSTOMER"), null, trustedRsaKey, "wrong-audience", null);

        mvc.perform(get("/api/v1/parcels/" + PARCEL_ID)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401));

        verifyNoInteractions(queryService);
    }

    @Test
    @DisplayName("Expired token returns 401")
    void expiredTokenReturns401() throws Exception {
        String token = issueToken(SENDER_SUB, List.of("CUSTOMER"), null, trustedRsaKey, "swiftdrop-api", Instant.now().minusSeconds(60));

        mvc.perform(get("/api/v1/parcels/" + PARCEL_ID)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401));

        verifyNoInteractions(queryService);
    }

    // --- Parcel Detail Authorization Tests ---

    @Test
    @DisplayName("Authorized customer reading own parcel returns 200 OK")
    void customerReadingOwnParcelReturns200() throws Exception {
        when(queryService.getParcelDetail(eq(PARCEL_ID), any(Jwt.class)))
                .thenReturn(sampleDetailResponse());

        String token = issueToken(SENDER_SUB, List.of("CUSTOMER"), null, trustedRsaKey, "swiftdrop-api", null);

        mvc.perform(get("/api/v1/parcels/" + PARCEL_ID)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.parcelId").value(PARCEL_ID.toString()))
                .andExpect(jsonPath("$.trackingNumber").value("SWD-20261025-A1B2C3"))
                .andExpect(jsonPath("$.createdBySub").value(SENDER_SUB))
                .andExpect(jsonPath("$.currentStatus").value("IN_TRANSIT"))
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.amountMinorUnits").value(350000))
                .andExpect(jsonPath("$.currency").value("LKR"))
                .andExpect(jsonPath("$.activeCourierSub").value(COURIER_SUB));
    }

    @Test
    @DisplayName("Customer reading other customer's parcel returns 403 Forbidden")
    void customerReadingOtherParcelReturns403() throws Exception {
        when(queryService.getParcelDetail(eq(PARCEL_ID), any(Jwt.class)))
                .thenThrow(new ParcelAccessDeniedException(PARCEL_ID));

        String token = issueToken("other-cust", List.of("CUSTOMER"), null, trustedRsaKey, "swiftdrop-api", null);

        mvc.perform(get("/api/v1/parcels/" + PARCEL_ID)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .accept(MediaType.APPLICATION_JSON, MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(status().isForbidden())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.title").value("Access Denied"))
                .andExpect(jsonPath("$.code").value("PARCEL_ACCESS_DENIED"));
    }

    @Test
    @DisplayName("Approved merchant reading own tenant parcel returns 200 OK")
    void approvedMerchantReturns200() throws Exception {
        when(queryService.getParcelDetail(eq(PARCEL_ID), any(Jwt.class)))
                .thenReturn(sampleDetailResponse());

        String token = issueToken("merchant-user", List.of("MERCHANT"), Map.of(
                "merchant_id", MERCHANT_ID.toString(),
                "merchant_status", "APPROVED"
        ), trustedRsaKey, "swiftdrop-api", null);

        mvc.perform(get("/api/v1/parcels/" + PARCEL_ID)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parcelId").value(PARCEL_ID.toString()));
    }

    @Test
    @DisplayName("Merchant crossing tenant boundary returns 403 Forbidden")
    void merchantCrossTenantReturns403() throws Exception {
        when(queryService.getParcelDetail(eq(PARCEL_ID), any(Jwt.class)))
                .thenThrow(new ParcelAccessDeniedException(PARCEL_ID));

        String token = issueToken("merchant-user", List.of("MERCHANT"), Map.of(
                "merchant_id", UUID.randomUUID().toString(),
                "merchant_status", "APPROVED"
        ), trustedRsaKey, "swiftdrop-api", null);

        mvc.perform(get("/api/v1/parcels/" + PARCEL_ID)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .accept(MediaType.APPLICATION_JSON, MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(status().isForbidden())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @DisplayName("Currently assigned courier returns 200 OK")
    void assignedCourierReturns200() throws Exception {
        when(queryService.getParcelDetail(eq(PARCEL_ID), any(Jwt.class)))
                .thenReturn(sampleDetailResponse());

        String token = issueToken(COURIER_SUB, List.of("COURIER"), null, trustedRsaKey, "swiftdrop-api", null);

        mvc.perform(get("/api/v1/parcels/" + PARCEL_ID)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parcelId").value(PARCEL_ID.toString()));
    }

    @Test
    @DisplayName("Unassigned courier returns 403 Forbidden")
    void unassignedCourierReturns403() throws Exception {
        when(queryService.getParcelDetail(eq(PARCEL_ID), any(Jwt.class)))
                .thenThrow(new ParcelAccessDeniedException(PARCEL_ID));

        String token = issueToken("other-courier", List.of("COURIER"), null, trustedRsaKey, "swiftdrop-api", null);

        mvc.perform(get("/api/v1/parcels/" + PARCEL_ID)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .accept(MediaType.APPLICATION_JSON, MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(status().isForbidden())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @DisplayName("Dispatcher returns 200 OK")
    void dispatcherReturns200() throws Exception {
        when(queryService.getParcelDetail(eq(PARCEL_ID), any(Jwt.class)))
                .thenReturn(sampleDetailResponse());

        String token = issueToken("dispatcher-user", List.of("DISPATCHER"), null, trustedRsaKey, "swiftdrop-api", null);

        mvc.perform(get("/api/v1/parcels/" + PARCEL_ID)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parcelId").value(PARCEL_ID.toString()));
    }

    @Test
    @DisplayName("Admin returns 200 OK")
    void adminReturns200() throws Exception {
        when(queryService.getParcelDetail(eq(PARCEL_ID), any(Jwt.class)))
                .thenReturn(sampleDetailResponse());

        String token = issueToken("admin-user", List.of("ADMIN"), null, trustedRsaKey, "swiftdrop-api", null);

        mvc.perform(get("/api/v1/parcels/" + PARCEL_ID)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parcelId").value(PARCEL_ID.toString()));
    }

    @Test
    @DisplayName("Non-existent parcel returns 404 ProblemDetail")
    void nonExistentParcelReturns404() throws Exception {
        UUID unknownId = UUID.randomUUID();
        when(queryService.getParcelDetail(eq(unknownId), any(Jwt.class)))
                .thenThrow(new ParcelNotFoundException(unknownId));

        String token = issueToken(SENDER_SUB, List.of("CUSTOMER"), null, trustedRsaKey, "swiftdrop-api", null);

        mvc.perform(get("/api/v1/parcels/" + unknownId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .accept(MediaType.APPLICATION_JSON, MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.title").value("Parcel Not Found"))
                .andExpect(jsonPath("$.code").value("PARCEL_NOT_FOUND"));
    }

    @Test
    @DisplayName("Malformed UUID parameter returns 400 ProblemDetail")
    void malformedUuidReturns400() throws Exception {
        String token = issueToken(SENDER_SUB, List.of("CUSTOMER"), null, trustedRsaKey, "swiftdrop-api", null);

        mvc.perform(get("/api/v1/parcels/not-a-valid-uuid")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .accept(MediaType.APPLICATION_JSON, MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.title").value("Invalid Request Parameter"))
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));

        verifyNoInteractions(queryService);
    }

    // --- Status History Read Tests ---

    @Test
    @DisplayName("Authorized history read returns deterministic chronological entries")
    void historyReadReturns200() throws Exception {
        ParcelStatusHistoryResponse h1 = new ParcelStatusHistoryResponse(
                UUID.randomUUID(), PARCEL_ID, null, ParcelStatus.CREATED,
                null, null, null, 1L, Instant.now().minusSeconds(100)
        );
        ParcelStatusHistoryResponse h2 = new ParcelStatusHistoryResponse(
                UUID.randomUUID(), PARCEL_ID, ParcelStatus.CREATED, ParcelStatus.PICKED_UP,
                null, "Picked up from customer", null, 2L, Instant.now().minusSeconds(50)
        );

        PagedResponse<ParcelStatusHistoryResponse> pagedResponse = new PagedResponse<>(
                List.of(h1, h2), 0, 20, 2, 1, false, false
        );
        when(queryService.getParcelHistory(eq(PARCEL_ID), any(Jwt.class), any()))
                .thenReturn(pagedResponse);

        String token = issueToken(SENDER_SUB, List.of("CUSTOMER"), null, trustedRsaKey, "swiftdrop-api", null);

        mvc.perform(get("/api/v1/parcels/" + PARCEL_ID + "/history?page=0&size=20")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].newStatus").value("CREATED"))
                .andExpect(jsonPath("$.items[0].resultingVersion").value(1))
                .andExpect(jsonPath("$.items[1].newStatus").value("PICKED_UP"))
                .andExpect(jsonPath("$.items[1].resultingVersion").value(2))
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    @DisplayName("Unauthorized caller reading history returns 403 Forbidden")
    void unauthorizedHistoryReturns403() throws Exception {
        when(queryService.getParcelHistory(eq(PARCEL_ID), any(Jwt.class), any()))
                .thenThrow(new ParcelAccessDeniedException(PARCEL_ID));

        String token = issueToken("other-cust", List.of("CUSTOMER"), null, trustedRsaKey, "swiftdrop-api", null);

        mvc.perform(get("/api/v1/parcels/" + PARCEL_ID + "/history")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .accept(MediaType.APPLICATION_JSON, MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(status().isForbidden())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(403));
    }

    // --- Caller Parcel List Tests ---

    @Test
    @DisplayName("Customer listing own parcels returns 200 OK with PagedResponse")
    void listParcelsReturns200() throws Exception {
        PagedResponse<ParcelDetailResponse> pagedResponse = new PagedResponse<>(
                List.of(sampleDetailResponse()), 0, 20, 1, 1, false, false
        );
        when(queryService.listParcelsForCaller(any(Jwt.class), any()))
                .thenReturn(pagedResponse);

        String token = issueToken(SENDER_SUB, List.of("CUSTOMER"), null, trustedRsaKey, "swiftdrop-api", null);

        mvc.perform(get("/api/v1/parcels?page=0&size=20")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.totalElements").value(1));
    }
}
