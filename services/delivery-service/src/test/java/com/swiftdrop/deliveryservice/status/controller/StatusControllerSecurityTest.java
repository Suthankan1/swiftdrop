package com.swiftdrop.deliveryservice.status.controller;

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
import com.swiftdrop.deliveryservice.security.ReceiptSecurityConfiguration;
import com.swiftdrop.deliveryservice.status.IllegalTransitionException;
import com.swiftdrop.deliveryservice.status.InvalidTransitionParametersException;
import com.swiftdrop.deliveryservice.status.StatusAccessDeniedException;
import com.swiftdrop.deliveryservice.status.StatusTransitionService;
import com.swiftdrop.deliveryservice.status.TerminalStateMutationException;
import com.swiftdrop.deliveryservice.status.VersionConflictException;
import com.swiftdrop.deliveryservice.status.dto.ParcelStatusChangeResponse;
import com.swiftdrop.deliveryservice.status.dto.UpdateStatusRequest;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = StatusController.class,
        excludeAutoConfiguration = org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration.class)
@Import({ReceiptSecurityConfiguration.class, StatusProblemHandler.class})
class StatusControllerSecurityTest {

    private static MockWebServer authServer;
    private static RSAKey trustedRsaKey;
    private static RSAKey untrustedRsaKey;
    private static String issuerUrl;

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private StatusTransitionService statusTransitionService;

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
    @DisplayName("1. Missing JWT token returns 401 Unauthorized ProblemDetail")
    void missingJwtReturns401() throws Exception {
        UUID parcelId = UUID.randomUUID();
        mvc.perform(patch("/api/v1/parcels/" + parcelId + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newStatus\":\"PICKED_UP\",\"expectedVersion\":1}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("status").value(401))
                .andExpect(jsonPath("title").value("Unauthorized"))
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"));

        verifyNoInteractions(statusTransitionService);
    }

    @Test
    @DisplayName("2. Untrusted signature token returns 401 Unauthorized")
    void untrustedSignatureReturns401() throws Exception {
        UUID parcelId = UUID.randomUUID();
        String token = createTestToken(issuerUrl, "swiftdrop-api", "courier-1", List.of("COURIER"), null, null, untrustedRsaKey);

        mvc.perform(patch("/api/v1/parcels/" + parcelId + "/status")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newStatus\":\"PICKED_UP\",\"expectedVersion\":1}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("status").value(401));

        verifyNoInteractions(statusTransitionService);
    }

    @Test
    @DisplayName("3. Expired token returns 401 Unauthorized")
    void expiredTokenReturns401() throws Exception {
        UUID parcelId = UUID.randomUUID();
        Instant past = Instant.now().minus(2, ChronoUnit.HOURS);
        String token = createTestToken(issuerUrl, "swiftdrop-api", "courier-1", List.of("COURIER"), past.minus(1, ChronoUnit.HOURS), past, trustedRsaKey);

        mvc.perform(patch("/api/v1/parcels/" + parcelId + "/status")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newStatus\":\"PICKED_UP\",\"expectedVersion\":1}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("status").value(401));

        verifyNoInteractions(statusTransitionService);
    }

    @Test
    @DisplayName("4. Wrong audience token returns 401 Unauthorized")
    void wrongAudienceReturns401() throws Exception {
        UUID parcelId = UUID.randomUUID();
        String token = createTestToken(issuerUrl, "wrong-aud", "courier-1", List.of("COURIER"), null, null, trustedRsaKey);

        mvc.perform(patch("/api/v1/parcels/" + parcelId + "/status")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newStatus\":\"PICKED_UP\",\"expectedVersion\":1}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("status").value(401));

        verifyNoInteractions(statusTransitionService);
    }

    @Test
    @DisplayName("5. Unassigned courier or unauthorized role returns 403 Forbidden ProblemDetail")
    void unauthorizedCourierReturns403() throws Exception {
        UUID parcelId = UUID.randomUUID();
        String courierSub = "unassigned-courier";
        String token = createTestToken(issuerUrl, "swiftdrop-api", courierSub, List.of("COURIER"), null, null, trustedRsaKey);

        when(statusTransitionService.updateStatus(eq(parcelId), any(UpdateStatusRequest.class), eq(courierSub), any()))
                .thenThrow(new StatusAccessDeniedException("Forbidden - courier " + courierSub + " is not actively assigned to parcel " + parcelId));

        mvc.perform(patch("/api/v1/parcels/" + parcelId + "/status")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newStatus\":\"PICKED_UP\",\"expectedVersion\":1}"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("status").value(403))
                .andExpect(jsonPath("title").value("Access Denied"))
                .andExpect(jsonPath("code").value("STATUS_TRANSITION_FORBIDDEN"));
    }

    @Test
    @DisplayName("6. Stale expectedVersion returns 409 Conflict ProblemDetail")
    void staleExpectedVersionReturns409() throws Exception {
        UUID parcelId = UUID.randomUUID();
        String courierSub = "courier-123";
        String token = createTestToken(issuerUrl, "swiftdrop-api", courierSub, List.of("COURIER"), null, null, trustedRsaKey);

        when(statusTransitionService.updateStatus(eq(parcelId), any(UpdateStatusRequest.class), eq(courierSub), any()))
                .thenThrow(new VersionConflictException(1L, 2L));

        mvc.perform(patch("/api/v1/parcels/" + parcelId + "/status")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newStatus\":\"PICKED_UP\",\"expectedVersion\":1}"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("status").value(409))
                .andExpect(jsonPath("title").value("Optimistic Lock Failure"))
                .andExpect(jsonPath("code").value("VERSION_CONFLICT"))
                .andExpect(jsonPath("expectedVersion").value(1))
                .andExpect(jsonPath("actualVersion").value(2));
    }

    @Test
    @DisplayName("7. Terminal state mutation returns 409 Conflict ProblemDetail")
    void terminalStateMutationReturns409() throws Exception {
        UUID parcelId = UUID.randomUUID();
        String courierSub = "courier-123";
        String token = createTestToken(issuerUrl, "swiftdrop-api", courierSub, List.of("COURIER"), null, null, trustedRsaKey);

        when(statusTransitionService.updateStatus(eq(parcelId), any(UpdateStatusRequest.class), eq(courierSub), any()))
                .thenThrow(new TerminalStateMutationException("Cannot transition from terminal state DELIVERED to OUT_FOR_DELIVERY"));

        mvc.perform(patch("/api/v1/parcels/" + parcelId + "/status")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newStatus\":\"OUT_FOR_DELIVERY\",\"expectedVersion\":3}"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("status").value(409))
                .andExpect(jsonPath("title").value("Terminal State Immutability Violation"))
                .andExpect(jsonPath("code").value("TERMINAL_STATE_IMMUTABLE"));
    }

    @Test
    @DisplayName("8. Illegal transition returns 422 Unprocessable Entity ProblemDetail")
    void illegalTransitionReturns422() throws Exception {
        UUID parcelId = UUID.randomUUID();
        String courierSub = "courier-123";
        String token = createTestToken(issuerUrl, "swiftdrop-api", courierSub, List.of("COURIER"), null, null, trustedRsaKey);

        when(statusTransitionService.updateStatus(eq(parcelId), any(UpdateStatusRequest.class), eq(courierSub), any()))
                .thenThrow(new IllegalTransitionException("Illegal transition from IN_TRANSIT to PICKED_UP"));

        mvc.perform(patch("/api/v1/parcels/" + parcelId + "/status")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newStatus\":\"PICKED_UP\",\"expectedVersion\":2}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("status").value(422))
                .andExpect(jsonPath("title").value("Illegal Status Transition"))
                .andExpect(jsonPath("code").value("ILLEGAL_STATUS_TRANSITION"));
    }

    @Test
    @DisplayName("9. Missing mandatory failure reason on FAILED returns 400 Bad Request ProblemDetail")
    void missingFailureReasonReturns400() throws Exception {
        UUID parcelId = UUID.randomUUID();
        String courierSub = "courier-123";
        String token = createTestToken(issuerUrl, "swiftdrop-api", courierSub, List.of("COURIER"), null, null, trustedRsaKey);

        when(statusTransitionService.updateStatus(eq(parcelId), any(UpdateStatusRequest.class), eq(courierSub), any()))
                .thenThrow(new InvalidTransitionParametersException("Failure reason is mandatory when transitioning to FAILED"));

        mvc.perform(patch("/api/v1/parcels/" + parcelId + "/status")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newStatus\":\"FAILED\",\"expectedVersion\":2}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("status").value(400))
                .andExpect(jsonPath("title").value("Invalid Transition Parameters"))
                .andExpect(jsonPath("code").value("INVALID_TRANSITION_PARAMETERS"));
    }

    @Test
    @DisplayName("10. Null newStatus payload returns 400 Bad Request with invalidParameters")
    void nullPayloadReturns400() throws Exception {
        UUID parcelId = UUID.randomUUID();
        String courierSub = "courier-123";
        String token = createTestToken(issuerUrl, "swiftdrop-api", courierSub, List.of("COURIER"), null, null, trustedRsaKey);

        mvc.perform(patch("/api/v1/parcels/" + parcelId + "/status")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":1}")) // missing newStatus
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("status").value(400))
                .andExpect(jsonPath("code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("invalidParameters").isArray());
    }

    @Test
    @DisplayName("11. Parcel not found returns 404 Not Found ProblemDetail")
    void parcelNotFoundReturns404() throws Exception {
        UUID parcelId = UUID.randomUUID();
        String courierSub = "courier-123";
        String token = createTestToken(issuerUrl, "swiftdrop-api", courierSub, List.of("COURIER"), null, null, trustedRsaKey);

        when(statusTransitionService.updateStatus(eq(parcelId), any(UpdateStatusRequest.class), eq(courierSub), any()))
                .thenThrow(new ParcelNotFoundException(parcelId));

        mvc.perform(patch("/api/v1/parcels/" + parcelId + "/status")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newStatus\":\"PICKED_UP\",\"expectedVersion\":1}"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("status").value(404))
                .andExpect(jsonPath("code").value("PARCEL_NOT_FOUND"));
    }

    @Test
    @DisplayName("12. Authorized courier transition returns 200 OK with ParcelStatusChangeResponse")
    void authorizedCourierReturns200() throws Exception {
        UUID parcelId = UUID.randomUUID();
        String courierSub = "assigned-courier-456";
        String token = createTestToken(issuerUrl, "swiftdrop-api", courierSub, List.of("COURIER"), null, null, trustedRsaKey);

        Instant now = Instant.now();
        ParcelStatusChangeResponse response = new ParcelStatusChangeResponse(
                parcelId,
                "SWD-20261025-X1Y2",
                "CREATED",
                "PICKED_UP",
                2L,
                2L,
                now
        );

        when(statusTransitionService.updateStatus(eq(parcelId), any(UpdateStatusRequest.class), eq(courierSub), any()))
                .thenReturn(response);

        mvc.perform(patch("/api/v1/parcels/" + parcelId + "/status")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newStatus\":\"PICKED_UP\",\"expectedVersion\":1,\"note\":\"Picked up from sender\"}"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("parcelId").value(parcelId.toString()))
                .andExpect(jsonPath("trackingNumber").value("SWD-20261025-X1Y2"))
                .andExpect(jsonPath("previousStatus").value("CREATED"))
                .andExpect(jsonPath("currentStatus").value("PICKED_UP"))
                .andExpect(jsonPath("stateVersion").value(2))
                .andExpect(jsonPath("updatedParcelVersion").value(2))
                .andExpect(jsonPath("occurredAt").isNotEmpty());
    }
}
