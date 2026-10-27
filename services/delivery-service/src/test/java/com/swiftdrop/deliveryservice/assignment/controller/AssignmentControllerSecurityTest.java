package com.swiftdrop.deliveryservice.assignment.controller;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.swiftdrop.deliveryservice.assignment.DeliveryAssignmentService;
import com.swiftdrop.deliveryservice.assignment.dto.AssignCourierRequest;
import com.swiftdrop.deliveryservice.assignment.dto.AssignmentResponse;
import com.swiftdrop.deliveryservice.assignment.exception.AssignmentAccessDeniedException;
import com.swiftdrop.deliveryservice.assignment.exception.AssignmentConflictException;
import com.swiftdrop.deliveryservice.assignment.exception.IneligibleCourierException;
import com.swiftdrop.deliveryservice.assignment.exception.InvalidParcelStateException;
import com.swiftdrop.deliveryservice.core.ParcelNotFoundException;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = AssignmentController.class,
        excludeAutoConfiguration = org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration.class)
@Import({ReceiptSecurityConfiguration.class, AssignmentProblemHandler.class})
class AssignmentControllerSecurityTest {

    private static MockWebServer authServer;
    private static RSAKey trustedRsaKey;
    private static RSAKey untrustedRsaKey;
    private static String issuerUrl;

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private DeliveryAssignmentService deliveryAssignmentService;

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
        mvc.perform(post("/api/v1/assignments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parcelId\":\"" + parcelId + "\",\"courierSub\":\"c-1\",\"expectedVersion\":1}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("status").value(401))
                .andExpect(jsonPath("title").value("Unauthorized"))
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"));

        verifyNoInteractions(deliveryAssignmentService);
    }

    @Test
    @DisplayName("2. Untrusted signature token returns 401 Unauthorized")
    void untrustedSignatureReturns401() throws Exception {
        UUID parcelId = UUID.randomUUID();
        String token = createTestToken(issuerUrl, "swiftdrop-api", "disp-1", List.of("DISPATCHER"), null, null, untrustedRsaKey);

        mvc.perform(post("/api/v1/assignments")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parcelId\":\"" + parcelId + "\",\"courierSub\":\"c-1\",\"expectedVersion\":1}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("status").value(401));

        verifyNoInteractions(deliveryAssignmentService);
    }

    @Test
    @DisplayName("3. Expired token returns 401 Unauthorized")
    void expiredTokenReturns401() throws Exception {
        UUID parcelId = UUID.randomUUID();
        Instant past = Instant.now().minus(2, ChronoUnit.HOURS);
        String token = createTestToken(issuerUrl, "swiftdrop-api", "disp-1", List.of("DISPATCHER"), past.minus(1, ChronoUnit.HOURS), past, trustedRsaKey);

        mvc.perform(post("/api/v1/assignments")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parcelId\":\"" + parcelId + "\",\"courierSub\":\"c-1\",\"expectedVersion\":1}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("status").value(401));

        verifyNoInteractions(deliveryAssignmentService);
    }

    @Test
    @DisplayName("4. Wrong audience token returns 401 Unauthorized")
    void wrongAudienceReturns401() throws Exception {
        UUID parcelId = UUID.randomUUID();
        String token = createTestToken(issuerUrl, "wrong-aud", "disp-1", List.of("DISPATCHER"), null, null, trustedRsaKey);

        mvc.perform(post("/api/v1/assignments")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parcelId\":\"" + parcelId + "\",\"courierSub\":\"c-1\",\"expectedVersion\":1}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("status").value(401));

        verifyNoInteractions(deliveryAssignmentService);
    }

    @Test
    @DisplayName("5. Non-dispatcher role (e.g. COURIER) returns 403 Forbidden ProblemDetail")
    void unauthorizedRoleReturns403() throws Exception {
        UUID parcelId = UUID.randomUUID();
        String callerSub = "courier-attempting-dispatch";
        String token = createTestToken(issuerUrl, "swiftdrop-api", callerSub, List.of("COURIER"), null, null, trustedRsaKey);

        when(deliveryAssignmentService.assignCourier(any(AssignCourierRequest.class), eq(callerSub), any()))
                .thenThrow(new AssignmentAccessDeniedException("Forbidden - DISPATCHER or ADMIN role required for courier assignment."));

        mvc.perform(post("/api/v1/assignments")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parcelId\":\"" + parcelId + "\",\"courierSub\":\"c-1\",\"expectedVersion\":1}"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("status").value(403))
                .andExpect(jsonPath("title").value("Access Denied"))
                .andExpect(jsonPath("code").value("ASSIGNMENT_FORBIDDEN"));
    }

    @Test
    @DisplayName("6. Ineligible courier returns 400 Bad Request ProblemDetail")
    void ineligibleCourierReturns400() throws Exception {
        UUID parcelId = UUID.randomUUID();
        String dispatcherSub = "dispatcher-007";
        String token = createTestToken(issuerUrl, "swiftdrop-api", dispatcherSub, List.of("DISPATCHER"), null, null, trustedRsaKey);

        when(deliveryAssignmentService.assignCourier(any(AssignCourierRequest.class), eq(dispatcherSub), any()))
                .thenThrow(new IneligibleCourierException("Courier c-suspended is not eligible for assignment: COURIER_SUSPENDED"));

        mvc.perform(post("/api/v1/assignments")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parcelId\":\"" + parcelId + "\",\"courierSub\":\"c-suspended\",\"expectedVersion\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("status").value(400))
                .andExpect(jsonPath("title").value("Ineligible Courier"))
                .andExpect(jsonPath("code").value("INELIGIBLE_COURIER"));
    }

    @Test
    @DisplayName("7. Non-CREATED parcel returns 400 Bad Request ProblemDetail")
    void invalidParcelStateReturns400() throws Exception {
        UUID parcelId = UUID.randomUUID();
        String dispatcherSub = "dispatcher-007";
        String token = createTestToken(issuerUrl, "swiftdrop-api", dispatcherSub, List.of("DISPATCHER"), null, null, trustedRsaKey);

        when(deliveryAssignmentService.assignCourier(any(AssignCourierRequest.class), eq(dispatcherSub), any()))
                .thenThrow(new InvalidParcelStateException("Parcel " + parcelId + " is in status IN_TRANSIT and cannot be assigned."));

        mvc.perform(post("/api/v1/assignments")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parcelId\":\"" + parcelId + "\",\"courierSub\":\"c-1\",\"expectedVersion\":2}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("status").value(400))
                .andExpect(jsonPath("title").value("Invalid Parcel State"))
                .andExpect(jsonPath("code").value("INVALID_PARCEL_STATE"));
    }

    @Test
    @DisplayName("8. Concurrency conflict (version mismatch or already assigned) returns 409 Conflict ProblemDetail")
    void concurrencyConflictReturns409() throws Exception {
        UUID parcelId = UUID.randomUUID();
        String dispatcherSub = "dispatcher-007";
        String token = createTestToken(issuerUrl, "swiftdrop-api", dispatcherSub, List.of("DISPATCHER"), null, null, trustedRsaKey);

        when(deliveryAssignmentService.assignCourier(any(AssignCourierRequest.class), eq(dispatcherSub), any()))
                .thenThrow(new AssignmentConflictException("Parcel " + parcelId + " already has an active courier assignment."));

        mvc.perform(post("/api/v1/assignments")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parcelId\":\"" + parcelId + "\",\"courierSub\":\"c-1\",\"expectedVersion\":1}"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("status").value(409))
                .andExpect(jsonPath("title").value("Assignment Conflict"))
                .andExpect(jsonPath("code").value("ASSIGNMENT_CONFLICT"));
    }

    @Test
    @DisplayName("9. Null required field returns 400 Bad Request with invalidParameters array")
    void nullPayloadReturns400() throws Exception {
        String dispatcherSub = "dispatcher-007";
        String token = createTestToken(issuerUrl, "swiftdrop-api", dispatcherSub, List.of("DISPATCHER"), null, null, trustedRsaKey);

        mvc.perform(post("/api/v1/assignments")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"courierSub\":\"c-1\",\"expectedVersion\":1}")) // missing parcelId
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("status").value(400))
                .andExpect(jsonPath("code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("invalidParameters").isArray());
    }

    @Test
    @DisplayName("10. Parcel not found returns 404 Not Found ProblemDetail")
    void parcelNotFoundReturns404() throws Exception {
        UUID parcelId = UUID.randomUUID();
        String dispatcherSub = "dispatcher-007";
        String token = createTestToken(issuerUrl, "swiftdrop-api", dispatcherSub, List.of("DISPATCHER"), null, null, trustedRsaKey);

        when(deliveryAssignmentService.assignCourier(any(AssignCourierRequest.class), eq(dispatcherSub), any()))
                .thenThrow(new ParcelNotFoundException(parcelId));

        mvc.perform(post("/api/v1/assignments")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parcelId\":\"" + parcelId + "\",\"courierSub\":\"c-1\",\"expectedVersion\":1}"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("status").value(404))
                .andExpect(jsonPath("code").value("PARCEL_NOT_FOUND"));
    }

    @Test
    @DisplayName("11. Authorized dispatcher assignment returns 200 OK with AssignmentResponse")
    void authorizedDispatcherReturns200() throws Exception {
        UUID parcelId = UUID.randomUUID();
        UUID assignmentId = UUID.randomUUID();
        String courierSub = "courier-sub-456";
        String dispatcherSub = "dispatcher-007";
        String token = createTestToken(issuerUrl, "swiftdrop-api", dispatcherSub, List.of("DISPATCHER"), null, null, trustedRsaKey);

        Instant now = Instant.now();
        AssignmentResponse response = new AssignmentResponse(
                assignmentId,
                parcelId,
                courierSub,
                true,
                now,
                dispatcherSub,
                2L
        );

        when(deliveryAssignmentService.assignCourier(any(AssignCourierRequest.class), eq(dispatcherSub), any()))
                .thenReturn(response);

        mvc.perform(post("/api/v1/assignments")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parcelId\":\"" + parcelId + "\",\"courierSub\":\"" + courierSub + "\",\"expectedVersion\":1}"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("assignmentId").value(assignmentId.toString()))
                .andExpect(jsonPath("parcelId").value(parcelId.toString()))
                .andExpect(jsonPath("courierSub").value(courierSub))
                .andExpect(jsonPath("active").value(true))
                .andExpect(jsonPath("assignedBy").value(dispatcherSub))
                .andExpect(jsonPath("newParcelVersion").value(2))
                .andExpect(jsonPath("assignedAt").isNotEmpty());
    }

    @Test
    @DisplayName("12. Authorized dispatcher reassignment returns 200 OK with AssignmentResponse")
    void authorizedReassignmentReturns200() throws Exception {
        UUID parcelId = UUID.randomUUID();
        UUID newAssignmentId = UUID.randomUUID();
        String replacementCourierSub = "courier-sub-repl";
        String dispatcherSub = "dispatcher-007";
        String token = createTestToken(issuerUrl, "swiftdrop-api", dispatcherSub, List.of("DISPATCHER"), null, null, trustedRsaKey);

        Instant now = Instant.now();
        AssignmentResponse response = new AssignmentResponse(
                newAssignmentId,
                parcelId,
                replacementCourierSub,
                true,
                now,
                dispatcherSub,
                3L,
                null,
                "BREAKDOWN"
        );

        when(deliveryAssignmentService.reassignCourier(any(com.swiftdrop.deliveryservice.assignment.dto.ReassignCourierRequest.class), eq(dispatcherSub), any()))
                .thenReturn(response);

        mvc.perform(post("/api/v1/assignments/reassign")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parcelId\":\"" + parcelId + "\",\"replacementCourierSub\":\"" + replacementCourierSub + "\",\"expectedVersion\":2,\"reason\":\"BREAKDOWN\"}"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("assignmentId").value(newAssignmentId.toString()))
                .andExpect(jsonPath("parcelId").value(parcelId.toString()))
                .andExpect(jsonPath("courierSub").value(replacementCourierSub))
                .andExpect(jsonPath("active").value(true))
                .andExpect(jsonPath("newParcelVersion").value(3))
                .andExpect(jsonPath("reason").value("BREAKDOWN"));
    }

    @Test
    @DisplayName("13. Unauthorized courier role denied reassignment with 403 Forbidden")
    void courierRoleDeniedReassignment403() throws Exception {
        UUID parcelId = UUID.randomUUID();
        String token = createTestToken(issuerUrl, "swiftdrop-api", "courier-user", List.of("COURIER"), null, null, trustedRsaKey);

        when(deliveryAssignmentService.reassignCourier(any(), any(), any()))
                .thenThrow(new AssignmentAccessDeniedException("Forbidden - DISPATCHER or ADMIN role required for courier assignment."));

        mvc.perform(post("/api/v1/assignments/reassign")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parcelId\":\"" + parcelId + "\",\"replacementCourierSub\":\"c-new\",\"expectedVersion\":2}"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("status").value(403))
                .andExpect(jsonPath("code").value("ASSIGNMENT_FORBIDDEN"));
    }

    @Test
    @DisplayName("14. Reassignment on picked up parcel returns 400 Bad Request ProblemDetail")
    void reassignPickedUpParcelReturns400() throws Exception {
        UUID parcelId = UUID.randomUUID();
        String dispatcherSub = "dispatcher-007";
        String token = createTestToken(issuerUrl, "swiftdrop-api", dispatcherSub, List.of("DISPATCHER"), null, null, trustedRsaKey);

        when(deliveryAssignmentService.reassignCourier(any(), eq(dispatcherSub), any()))
                .thenThrow(new InvalidParcelStateException("Parcel " + parcelId + " is in status PICKED_UP and cannot be reassigned."));

        mvc.perform(post("/api/v1/assignments/reassign")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parcelId\":\"" + parcelId + "\",\"replacementCourierSub\":\"c-new\",\"expectedVersion\":2}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("status").value(400))
                .andExpect(jsonPath("code").value("INVALID_PARCEL_STATE"));
    }

    @Test
    @DisplayName("15. Authorized dispatcher closure returns 200 OK with active=false")
    void authorizedClosureReturns200() throws Exception {
        UUID parcelId = UUID.randomUUID();
        UUID assignmentId = UUID.randomUUID();
        String courierSub = "courier-sub-123";
        String dispatcherSub = "dispatcher-007";
        String token = createTestToken(issuerUrl, "swiftdrop-api", dispatcherSub, List.of("DISPATCHER"), null, null, trustedRsaKey);

        Instant now = Instant.now();
        AssignmentResponse response = new AssignmentResponse(
                assignmentId,
                parcelId,
                courierSub,
                false,
                now.minusSeconds(100),
                dispatcherSub,
                3L,
                now,
                "UNASSIGNED"
        );

        when(deliveryAssignmentService.closeAssignment(any(com.swiftdrop.deliveryservice.assignment.dto.CloseAssignmentRequest.class), eq(dispatcherSub), any()))
                .thenReturn(response);

        mvc.perform(post("/api/v1/assignments/close")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parcelId\":\"" + parcelId + "\",\"expectedVersion\":2,\"reason\":\"UNASSIGNED\"}"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("assignmentId").value(assignmentId.toString()))
                .andExpect(jsonPath("active").value(false))
                .andExpect(jsonPath("endedAt").isNotEmpty());
    }

    @Test
    @DisplayName("16. Authorized dispatcher retrieves assignment audit history")
    void authorizedAuditHistoryReturns200() throws Exception {
        UUID parcelId = UUID.randomUUID();
        String dispatcherSub = "dispatcher-007";
        String token = createTestToken(issuerUrl, "swiftdrop-api", dispatcherSub, List.of("DISPATCHER"), null, null, trustedRsaKey);

        com.swiftdrop.deliveryservice.assignment.dto.AssignmentAuditResponse audit =
                new com.swiftdrop.deliveryservice.assignment.dto.AssignmentAuditResponse(
                        UUID.randomUUID(),
                        parcelId,
                        UUID.randomUUID(),
                        "courier-sub",
                        "REASSIGNED",
                        dispatcherSub,
                        "DISPATCHER",
                        "prev-courier",
                        UUID.randomUUID(),
                        "BREAKDOWN",
                        3L,
                        Instant.now()
                );

        when(deliveryAssignmentService.getAssignmentAuditHistory(eq(parcelId), eq(dispatcherSub), any()))
                .thenReturn(List.of(audit));

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/assignments/parcels/" + parcelId + "/history")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].action").value("REASSIGNED"))
                .andExpect(jsonPath("$[0].previousCourierSub").value("prev-courier"));
    }

    @Test
    @DisplayName("17. Non-dispatcher role denied access to audit history with 403 Forbidden")
    void nonDispatcherDeniedAuditHistory403() throws Exception {
        UUID parcelId = UUID.randomUUID();
        String token = createTestToken(issuerUrl, "swiftdrop-api", "customer-1", List.of("CUSTOMER"), null, null, trustedRsaKey);

        when(deliveryAssignmentService.getAssignmentAuditHistory(eq(parcelId), any(), any()))
                .thenThrow(new AssignmentAccessDeniedException("Forbidden - DISPATCHER or ADMIN role required for courier assignment."));

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/assignments/parcels/" + parcelId + "/history")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("status").value(403))
                .andExpect(jsonPath("code").value("ASSIGNMENT_FORBIDDEN"));
    }
}
