package com.swiftdrop.deliveryservice.booking;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.swiftdrop.deliveryservice.core.ParcelStatus;
import com.swiftdrop.deliveryservice.query.dto.ParcelDetailResponse;
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
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = ParcelBookingController.class,
        excludeAutoConfiguration = org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration.class)
@Import(ReceiptSecurityConfiguration.class)
class ParcelBookingControllerSecurityTest {

    private static MockWebServer authServer;
    private static RSAKey trustedRsaKey;
    private static String issuerUrl;

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ParcelBookingService bookingService;

    private static final UUID QUOTE_ID = UUID.fromString("10b11aef-6bb3-4fb0-a07d-36e98a7a4f32");
    private static final String SENDER_SUB = "cust-alice-sub";

    @BeforeAll
    static void setUpServers() throws Exception {
        trustedRsaKey = new RSAKeyGenerator(2048)
                .keyID("trusted-booking-key")
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

    private String issueToken(String subject, List<String> roles) throws Exception {
        JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
                .subject(subject)
                .issuer(issuerUrl)
                .audience("swiftdrop-api")
                .issueTime(new Date())
                .expirationTime(Date.from(Instant.now().plus(1, ChronoUnit.HOURS)));

        if (roles != null) {
            builder.claim("roles", roles);
            builder.claim("realm_access", Map.of("roles", roles));
        }

        SignedJWT signedJWT = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(trustedRsaKey.getKeyID()).build(),
                builder.build()
        );
        signedJWT.sign(new RSASSASigner(trustedRsaKey));
        return signedJWT.serialize();
    }

    @Test
    @DisplayName("Anonymous caller without Bearer token is rejected with 401 Unauthorized")
    void anonymousCallerRejected() throws Exception {
        mvc.perform(post("/api/v1/parcels")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {
                          "quoteId": "%s",
                          "pickupAddress": "123 Galle Rd, Colombo",
                          "dropoffAddress": "45 Kandy Rd, Gampaha",
                          "recipientName": "Perera",
                          "recipientPhone": "+94771234567"
                        }
                        """.formatted(QUOTE_ID)))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"));

        verifyNoInteractions(bookingService);
    }

    @Test
    @DisplayName("Caller with COURIER role is rejected with 403 Forbidden")
    void courierRoleRejected() throws Exception {
        String token = issueToken("courier-sub", List.of("COURIER"));

        mvc.perform(post("/api/v1/parcels")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {
                          "quoteId": "%s",
                          "pickupAddress": "123 Galle Rd, Colombo",
                          "dropoffAddress": "45 Kandy Rd, Gampaha",
                          "recipientName": "Perera",
                          "recipientPhone": "+94771234567"
                        }
                        """.formatted(QUOTE_ID)))
                .andExpect(status().isForbidden());

        verifyNoInteractions(bookingService);
    }

    @Test
    @DisplayName("Authenticated CUSTOMER successfully books parcel (201 Created)")
    void customerBookingSuccess() throws Exception {
        String token = issueToken(SENDER_SUB, List.of("CUSTOMER"));
        UUID parcelId = UUID.randomUUID();
        String idempotencyKey = UUID.randomUUID().toString();

        ParcelDetailResponse mockResponse = new ParcelDetailResponse(
                parcelId,
                "SWD-20261027-ABC12345",
                SENDER_SUB,
                null,
                ParcelStatus.CREATED,
                1L,
                2.5,
                "STANDARD",
                59000L,
                "LKR",
                "RATE-2026-V1",
                "123 Galle Rd, Colombo",
                "45 Kandy Rd, Gampaha",
                null,
                Instant.now()
        );

        when(bookingService.bookParcel(any(), eq(SENDER_SUB), any(), eq(idempotencyKey)))
                .thenReturn(mockResponse);

        mvc.perform(post("/api/v1/parcels")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {
                          "quoteId": "%s",
                          "pickupAddress": "123 Galle Rd, Colombo",
                          "dropoffAddress": "45 Kandy Rd, Gampaha",
                          "recipientName": "Perera",
                          "recipientPhone": "+94771234567"
                        }
                        """.formatted(QUOTE_ID)))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, "/api/v1/parcels/" + parcelId))
                .andExpect(jsonPath("$.parcelId").value(parcelId.toString()))
                .andExpect(jsonPath("$.trackingNumber").value("SWD-20261027-ABC12345"))
                .andExpect(jsonPath("$.currentStatus").value("CREATED"))
                .andExpect(jsonPath("$.amountMinorUnits").value(59000))
                .andExpect(jsonPath("$.currency").value("LKR"));
    }

    @Test
    @DisplayName("Expired quote returns 422 Unprocessable Entity")
    void expiredQuoteFails() throws Exception {
        String token = issueToken(SENDER_SUB, List.of("CUSTOMER"));

        when(bookingService.bookParcel(any(), eq(SENDER_SUB), any(), any()))
                .thenThrow(new BookingFailure(422, "QUOTE_EXPIRED", "Quote has expired. Please recalculate your quote."));

        mvc.perform(post("/api/v1/parcels")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {
                          "quoteId": "%s",
                          "pickupAddress": "123 Galle Rd, Colombo",
                          "dropoffAddress": "45 Kandy Rd, Gampaha",
                          "recipientName": "Perera",
                          "recipientPhone": "+94771234567"
                        }
                        """.formatted(QUOTE_ID)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("QUOTE_EXPIRED"))
                .andExpect(jsonPath("$.detail").value("Quote has expired. Please recalculate your quote."));
    }

    @Test
    @DisplayName("Consumed quote returns 409 Conflict")
    void consumedQuoteFails() throws Exception {
        String token = issueToken(SENDER_SUB, List.of("CUSTOMER"));

        when(bookingService.bookParcel(any(), eq(SENDER_SUB), any(), any()))
                .thenThrow(new BookingFailure(409, "QUOTE_ALREADY_CONSUMED", "This quote has already been consumed for a previous booking."));

        mvc.perform(post("/api/v1/parcels")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {
                          "quoteId": "%s",
                          "pickupAddress": "123 Galle Rd, Colombo",
                          "dropoffAddress": "45 Kandy Rd, Gampaha",
                          "recipientName": "Perera",
                          "recipientPhone": "+94771234567"
                        }
                        """.formatted(QUOTE_ID)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("QUOTE_ALREADY_CONSUMED"));
    }

    @Test
    @DisplayName("Missing required fields returns 400 Bad Request")
    void missingFieldsReturns400() throws Exception {
        String token = issueToken(SENDER_SUB, List.of("CUSTOMER"));

        mvc.perform(post("/api/v1/parcels")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        verifyNoInteractions(bookingService);
    }
}

