package com.swiftdrop.deliveryservice.sse.controller;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.swiftdrop.deliveryservice.core.Parcel;
import com.swiftdrop.deliveryservice.core.ParcelRepository;
import com.swiftdrop.deliveryservice.query.service.ParcelAuthorizationService;
import com.swiftdrop.deliveryservice.security.ReceiptSecurityConfiguration;
import com.swiftdrop.deliveryservice.sse.exception.SseConnectionLimitExceededException;
import com.swiftdrop.deliveryservice.sse.service.ParcelSseConnectionManager;
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
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = ParcelSseController.class,
        excludeAutoConfiguration = org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration.class)
@Import({ReceiptSecurityConfiguration.class, ParcelSseProblemHandler.class})
class ParcelSseControllerSecurityTest {

    private static MockWebServer authServer;
    private static RSAKey trustedRsaKey;
    private static RSAKey untrustedRsaKey;
    private static String issuerUrl;

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ParcelRepository parcelRepository;

    @MockitoBean
    private ParcelAuthorizationService authorizationService;

    @MockitoBean
    private ParcelSseConnectionManager connectionManager;

    private static final UUID PARCEL_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String SENDER_SUB = "customer-alice";
    private static final String COURIER_SUB = "courier-bob";
    private static final String ATTACKER_SUB = "attacker-eve";

    @BeforeAll
    static void setUpServers() throws Exception {
        trustedRsaKey = new RSAKeyGenerator(2048).keyID("trusted-key-id").generate();
        untrustedRsaKey = new RSAKeyGenerator(2048).keyID("untrusted-key-id").generate();

        authServer = new MockWebServer();
        authServer.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                if ("/realms/swiftdrop/protocol/openid-connect/certs".equals(request.getPath())) {
                    String jwksJson = new JWKSet(trustedRsaKey.toPublicJWK()).toString();
                    return new MockResponse()
                            .setHeader("Content-Type", "application/json")
                            .setBody(jwksJson);
                }
                return new MockResponse().setResponseCode(404);
            }
        });
        authServer.start();
        issuerUrl = authServer.url("/realms/swiftdrop").toString();
    }

    @AfterAll
    static void tearDownServers() throws IOException {
        if (authServer != null) {
            authServer.shutdown();
        }
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("KEYCLOAK_ISSUER_URI", () -> issuerUrl);
        registry.add("KEYCLOAK_JWKS_URI", () -> authServer.url("/realms/swiftdrop/protocol/openid-connect/certs").toString());
        registry.add("JWT_AUDIENCE", () -> "swiftdrop-api");
    }

    private String buildToken(String sub, List<String> roles, RSAKey signingKey, Instant expiresAt, String audience) throws Exception {
        JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
                .issuer(issuerUrl)
                .subject(sub)
                .audience(audience)
                .issueTime(new Date())
                .expirationTime(Date.from(expiresAt))
                .claim("realm_access", Map.of("roles", roles));

        SignedJWT signedJWT = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).build(),
                builder.build()
        );
        signedJWT.sign(new RSASSASigner(signingKey.toRSAPrivateKey()));
        return signedJWT.serialize();
    }

    private Parcel createParcelFixture() {
        return new Parcel(
                PARCEL_ID,
                "SWD-20261027-1111",
                SENDER_SUB,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "STANDARD",
                250000L,
                "LKR",
                new BigDecimal("2.500")
        );
    }

    @Test
    @DisplayName("1. GET /events without token returns 401 Unauthorized")
    void streamEvents_withoutToken_returns401Unauthorized() throws Exception {
        mvc.perform(get("/api/v1/parcels/" + PARCEL_ID + "/events"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"));

        verifyNoInteractions(parcelRepository);
        verifyNoInteractions(authorizationService);
        verifyNoInteractions(connectionManager);
    }

    @Test
    @DisplayName("2. GET /events with expired token returns 401 Unauthorized")
    void streamEvents_withExpiredToken_returns401Unauthorized() throws Exception {
        String expiredToken = buildToken(
                SENDER_SUB,
                List.of("CUSTOMER"),
                trustedRsaKey,
                Instant.now().minus(10, ChronoUnit.MINUTES),
                "swiftdrop-api"
        );

        mvc.perform(get("/api/v1/parcels/" + PARCEL_ID + "/events")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + expiredToken))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(parcelRepository);
        verifyNoInteractions(authorizationService);
        verifyNoInteractions(connectionManager);
    }

    @Test
    @DisplayName("3. GET /events with untrusted signing key returns 401 Unauthorized")
    void streamEvents_withUntrustedSigningKey_returns401Unauthorized() throws Exception {
        String forgedToken = buildToken(
                SENDER_SUB,
                List.of("CUSTOMER"),
                untrustedRsaKey,
                Instant.now().plus(1, ChronoUnit.HOURS),
                "swiftdrop-api"
        );

        mvc.perform(get("/api/v1/parcels/" + PARCEL_ID + "/events")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + forgedToken))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(parcelRepository);
        verifyNoInteractions(authorizationService);
        verifyNoInteractions(connectionManager);
    }

    @Test
    @DisplayName("4. GET /events with invalid audience returns 401 Unauthorized")
    void streamEvents_withInvalidAudience_returns401Unauthorized() throws Exception {
        String wrongAudienceToken = buildToken(
                SENDER_SUB,
                List.of("CUSTOMER"),
                trustedRsaKey,
                Instant.now().plus(1, ChronoUnit.HOURS),
                "other-service"
        );

        mvc.perform(get("/api/v1/parcels/" + PARCEL_ID + "/events")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + wrongAudienceToken))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(parcelRepository);
        verifyNoInteractions(authorizationService);
        verifyNoInteractions(connectionManager);
    }

    @Test
    @DisplayName("5. GET /events for nonexistent parcel returns 404 Not Found")
    void streamEvents_withNonexistentParcel_returns404NotFound() throws Exception {
        String validToken = buildToken(
                SENDER_SUB,
                List.of("CUSTOMER"),
                trustedRsaKey,
                Instant.now().plus(1, ChronoUnit.HOURS),
                "swiftdrop-api"
        );

        when(parcelRepository.findById(PARCEL_ID)).thenReturn(Optional.empty());

        mvc.perform(get("/api/v1/parcels/" + PARCEL_ID + "/events")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE))
                .andExpect(jsonPath("$.code").value("PARCEL_NOT_FOUND"))
                .andExpect(jsonPath("$.status").value(404));

        verifyNoInteractions(authorizationService);
        verifyNoInteractions(connectionManager);
    }

    @Test
    @DisplayName("6. GET /events for unauthorized customer / wrong owner returns 403 Forbidden")
    void streamEvents_unauthorizedCustomer_returns403Forbidden() throws Exception {
        String attackerToken = buildToken(
                ATTACKER_SUB,
                List.of("CUSTOMER"),
                trustedRsaKey,
                Instant.now().plus(1, ChronoUnit.HOURS),
                "swiftdrop-api"
        );

        Parcel parcel = createParcelFixture();
        when(parcelRepository.findById(PARCEL_ID)).thenReturn(Optional.of(parcel));
        when(authorizationService.isAuthorizedToRead(any(Jwt.class), eq(parcel))).thenReturn(false);

        mvc.perform(get("/api/v1/parcels/" + PARCEL_ID + "/events")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + attackerToken))
                .andExpect(status().isForbidden())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE))
                .andExpect(jsonPath("$.code").value("PARCEL_ACCESS_DENIED"))
                .andExpect(jsonPath("$.status").value(403));

        verifyNoInteractions(connectionManager);
    }

    @Test
    @DisplayName("7. GET /events for authorized sender returns 200 OK text/event-stream")
    void streamEvents_authorizedSender_returns200SseStream() throws Exception {
        String validToken = buildToken(
                SENDER_SUB,
                List.of("CUSTOMER"),
                trustedRsaKey,
                Instant.now().plus(1, ChronoUnit.HOURS),
                "swiftdrop-api"
        );

        Parcel parcel = createParcelFixture();
        when(parcelRepository.findById(PARCEL_ID)).thenReturn(Optional.of(parcel));
        when(authorizationService.isAuthorizedToRead(any(Jwt.class), eq(parcel))).thenReturn(true);
        when(connectionManager.registerSession(eq(PARCEL_ID), eq(SENDER_SUB), eq(false), any(Instant.class)))
                .thenReturn(new SseEmitter(60000L));

        mvc.perform(get("/api/v1/parcels/" + PARCEL_ID + "/events")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.TEXT_EVENT_STREAM_VALUE));
    }

    @Test
    @DisplayName("8. GET /events for assigned courier returns 200 OK text/event-stream")
    void streamEvents_assignedCourier_returns200SseStream() throws Exception {
        String courierToken = buildToken(
                COURIER_SUB,
                List.of("COURIER"),
                trustedRsaKey,
                Instant.now().plus(1, ChronoUnit.HOURS),
                "swiftdrop-api"
        );

        Parcel parcel = createParcelFixture();
        when(parcelRepository.findById(PARCEL_ID)).thenReturn(Optional.of(parcel));
        when(authorizationService.isAuthorizedToRead(any(Jwt.class), eq(parcel))).thenReturn(true);
        when(connectionManager.registerSession(eq(PARCEL_ID), eq(COURIER_SUB), eq(true), any(Instant.class)))
                .thenReturn(new SseEmitter(60000L));

        mvc.perform(get("/api/v1/parcels/" + PARCEL_ID + "/events")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + courierToken))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.TEXT_EVENT_STREAM_VALUE));
    }

    @Test
    @DisplayName("9. GET /events for DISPATCHER returns 200 OK text/event-stream")
    void streamEvents_dispatcher_returns200SseStream() throws Exception {
        String dispatcherToken = buildToken(
                "dispatcher-1",
                List.of("DISPATCHER"),
                trustedRsaKey,
                Instant.now().plus(1, ChronoUnit.HOURS),
                "swiftdrop-api"
        );

        Parcel parcel = createParcelFixture();
        when(parcelRepository.findById(PARCEL_ID)).thenReturn(Optional.of(parcel));
        when(authorizationService.isAuthorizedToRead(any(Jwt.class), eq(parcel))).thenReturn(true);
        when(connectionManager.registerSession(eq(PARCEL_ID), eq("dispatcher-1"), eq(false), any(Instant.class)))
                .thenReturn(new SseEmitter(60000L));

        mvc.perform(get("/api/v1/parcels/" + PARCEL_ID + "/events")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + dispatcherToken))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.TEXT_EVENT_STREAM_VALUE));
    }

    @Test
    @DisplayName("10. GET /events when connection limit exceeded returns 429 Too Many Requests")
    void streamEvents_whenConnectionLimitExceeded_returns429TooManyRequests() throws Exception {
        String validToken = buildToken(
                SENDER_SUB,
                List.of("CUSTOMER"),
                trustedRsaKey,
                Instant.now().plus(1, ChronoUnit.HOURS),
                "swiftdrop-api"
        );

        Parcel parcel = createParcelFixture();
        when(parcelRepository.findById(PARCEL_ID)).thenReturn(Optional.of(parcel));
        when(authorizationService.isAuthorizedToRead(any(Jwt.class), eq(parcel))).thenReturn(true);
        when(connectionManager.registerSession(any(UUID.class), any(), anyBoolean(), any(Instant.class)))
                .thenThrow(new SseConnectionLimitExceededException("GLOBAL", 100, "Global SSE connection limit reached (max: 100)"));

        mvc.perform(get("/api/v1/parcels/" + PARCEL_ID + "/events")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken))
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE))
                .andExpect(jsonPath("$.code").value("SSE_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.limitType").value("GLOBAL"))
                .andExpect(jsonPath("$.maxAllowed").value(100))
                .andExpect(jsonPath("$.status").value(429));
    }
}
