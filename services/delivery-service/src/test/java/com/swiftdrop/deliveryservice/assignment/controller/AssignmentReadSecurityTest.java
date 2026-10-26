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
import com.swiftdrop.deliveryservice.assignment.dto.AssignmentResponse;
import com.swiftdrop.deliveryservice.assignment.exception.AssignmentAccessDeniedException;
import com.swiftdrop.deliveryservice.query.dto.PagedResponse;
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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = AssignmentController.class,
        excludeAutoConfiguration = org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration.class)
@Import({ReceiptSecurityConfiguration.class, AssignmentProblemHandler.class})
class AssignmentReadSecurityTest {

    private static MockWebServer authServer;
    private static RSAKey trustedRsaKey;
    private static String issuerUrl;

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private DeliveryAssignmentService assignmentService;

    private static final String COURIER_SUB = "courier-sub-101";
    private static final UUID ASSIGNMENT_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID PARCEL_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    @BeforeAll
    static void setUpServers() throws Exception {
        trustedRsaKey = new RSAKeyGenerator(2048)
                .keyID("trusted-assignment-read-key")
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
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(subject)
                .issuer(issuerUrl)
                .audience("swiftdrop-api")
                .issueTime(new Date())
                .expirationTime(Date.from(Instant.now().plus(1, ChronoUnit.HOURS)))
                .claim("roles", roles)
                .build();

        SignedJWT signedJWT = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(trustedRsaKey.getKeyID()).build(),
                claims
        );
        signedJWT.sign(new RSASSASigner(trustedRsaKey));
        return signedJWT.serialize();
    }

    @Test
    @DisplayName("Unauthenticated GET /api/v1/assignments/my returns 401")
    void unauthenticatedReturns401() throws Exception {
        mvc.perform(get("/api/v1/assignments/my")
                        .accept(MediaType.APPLICATION_JSON, MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(jsonPath("$.status").value(401));

        verifyNoInteractions(assignmentService);
    }

    @Test
    @DisplayName("Authorized courier reading own active assignments returns 200 OK")
    void courierReadingOwnAssignmentsReturns200() throws Exception {
        AssignmentResponse item = new AssignmentResponse(
                ASSIGNMENT_ID,
                PARCEL_ID,
                COURIER_SUB,
                true,
                Instant.now(),
                "dispatcher-1",
                null
        );
        PagedResponse<AssignmentResponse> pagedResponse = new PagedResponse<>(
                List.of(item), 0, 20, 1, 1, false, false
        );

        when(assignmentService.getMyAssignments(eq(COURIER_SUB), any(), eq(true), any()))
                .thenReturn(pagedResponse);

        String token = issueToken(COURIER_SUB, List.of("COURIER"));

        mvc.perform(get("/api/v1/assignments/my?activeOnly=true&page=0&size=20")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].assignmentId").value(ASSIGNMENT_ID.toString()))
                .andExpect(jsonPath("$.items[0].courierSub").value(COURIER_SUB))
                .andExpect(jsonPath("$.items[0].active").value(true))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @DisplayName("GET /api/v1/assignments also returns courier assignments")
    void baseAssignmentsEndpointReturns200() throws Exception {
        PagedResponse<AssignmentResponse> emptyResponse = new PagedResponse<>(
                List.of(), 0, 20, 0, 0, false, false
        );

        when(assignmentService.getMyAssignments(eq(COURIER_SUB), any(), anyBoolean(), any()))
                .thenReturn(emptyResponse);

        String token = issueToken(COURIER_SUB, List.of("COURIER"));

        mvc.perform(get("/api/v1/assignments")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    @DisplayName("Non-courier (CUSTOMER role) calling own-assignment read returns 403 Forbidden")
    void customerCallingAssignmentsReturns403() throws Exception {
        when(assignmentService.getMyAssignments(any(), any(), anyBoolean(), any()))
                .thenThrow(new AssignmentAccessDeniedException("Caller does not hold required COURIER role."));

        String token = issueToken("customer-user", List.of("CUSTOMER"));

        mvc.perform(get("/api/v1/assignments/my")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .accept(MediaType.APPLICATION_JSON, MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(status().isForbidden())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.title").value("Access Denied"))
                .andExpect(jsonPath("$.code").value("ASSIGNMENT_FORBIDDEN"));
    }
}
