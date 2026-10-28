package com.swiftdrop.deliveryservice.tracking.controller;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.swiftdrop.deliveryservice.core.ParcelStatus;
import com.swiftdrop.deliveryservice.security.ReceiptSecurityConfiguration;
import com.swiftdrop.deliveryservice.tracking.dto.PublicTrackingResponse;
import com.swiftdrop.deliveryservice.tracking.dto.TrackingTimelineItem;
import com.swiftdrop.deliveryservice.tracking.exception.InvalidTrackingReferenceException;
import com.swiftdrop.deliveryservice.tracking.exception.TrackingNotFoundException;
import com.swiftdrop.deliveryservice.tracking.service.PublicTrackingService;
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

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * WebMvc security test for PublicTrackingController.
 * Verifies anonymous access is allowed for GET /api/v1/tracking/{trackingNumber},
 * non-GET methods are denied, appropriate caching headers are set, and private APIs
 * remain strictly authenticated.
 */
@WebMvcTest(controllers = PublicTrackingController.class,
        excludeAutoConfiguration = org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration.class)
@Import({ReceiptSecurityConfiguration.class, PublicTrackingProblemHandler.class})
class PublicTrackingControllerSecurityTest {

    private static MockWebServer authServer;
    private static RSAKey trustedRsaKey;
    private static String issuerUrl;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PublicTrackingService trackingService;

    @BeforeAll
    static void setUpServers() throws Exception {
        trustedRsaKey = new RSAKeyGenerator(2048)
                .keyID("swiftdrop-tracking-key")
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

    @Test
    @DisplayName("1. Unauthenticated anonymous caller can access valid tracking endpoint")
    void anonymousUser_CanAccessTrackingEndpoint() throws Exception {
        String trackingNumber = "SWD-20261023-A1B2C3";
        PublicTrackingResponse response = new PublicTrackingResponse(
                trackingNumber,
                ParcelStatus.IN_TRANSIT,
                "Colombo",
                "Gampaha",
                List.of(
                        new TrackingTimelineItem(ParcelStatus.CREATED, Instant.parse("2026-10-23T08:00:00Z"), "Colombo Sorting Hub"),
                        new TrackingTimelineItem(ParcelStatus.IN_TRANSIT, Instant.parse("2026-10-23T11:00:00Z"), "Western Province Sorting Hub")
                )
        );

        when(trackingService.getPublicTracking(trackingNumber)).thenReturn(response);

        mockMvc.perform(get("/api/v1/tracking/{trackingNumber}", trackingNumber)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store, must-revalidate"))
                .andExpect(header().string("X-Robots-Tag", "noindex, nofollow"))
                .andExpect(header().string(HttpHeaders.VARY, "Accept-Encoding"))
                .andExpect(jsonPath("$.trackingNumber").value(trackingNumber))
                .andExpect(jsonPath("$.currentStatus").value("IN_TRANSIT"))
                .andExpect(jsonPath("$.originCity").value("Colombo"))
                .andExpect(jsonPath("$.destinationCity").value("Gampaha"))
                .andExpect(jsonPath("$.timeline").isArray())
                .andExpect(jsonPath("$.timeline.length()").value(2))
                .andExpect(jsonPath("$.timeline[0].status").value("CREATED"))
                .andExpect(jsonPath("$.timeline[1].status").value("IN_TRANSIT"));
    }

    @Test
    @DisplayName("2. Unknown tracking reference returns 404 ProblemDetail without leaking internal state")
    void unknownTrackingReference_Returns404ProblemDetail() throws Exception {
        String unknown = "SWD-20261023-NOTFOUND";
        when(trackingService.getPublicTracking(unknown)).thenThrow(new TrackingNotFoundException(unknown));

        mockMvc.perform(get("/api/v1/tracking/{trackingNumber}", unknown)
                        .accept(MediaType.APPLICATION_PROBLEM_JSON, MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Content-Type", MediaType.APPLICATION_PROBLEM_JSON_VALUE))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.title").value("Tracking Reference Not Found"))
                .andExpect(jsonPath("$.code").value("TRACKING_NOT_FOUND"))
                .andExpect(jsonPath("$.detail").value("Tracking reference '" + unknown + "' was not found."));
    }

    @Test
    @DisplayName("3. Malformed tracking reference returns 400 ProblemDetail")
    void malformedTrackingReference_Returns400ProblemDetail() throws Exception {
        String invalid = "BAD-TRACKING-123";
        when(trackingService.getPublicTracking(invalid)).thenThrow(new InvalidTrackingReferenceException("Tracking reference must match approved format"));

        mockMvc.perform(get("/api/v1/tracking/{trackingNumber}", invalid)
                        .accept(MediaType.APPLICATION_PROBLEM_JSON, MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Content-Type", MediaType.APPLICATION_PROBLEM_JSON_VALUE))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.title").value("Invalid Tracking Reference"))
                .andExpect(jsonPath("$.code").value("INVALID_TRACKING_REFERENCE"));
    }

    @Test
    @DisplayName("4. Non-GET methods on /api/v1/tracking/** are denied")
    void postOnTrackingEndpoint_IsDenied() throws Exception {
        mockMvc.perform(post("/api/v1/tracking/SWD-20261023-A1B2C3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().is4xxClientError());
    }
}
