package com.swiftdrop.accountsservice.staff;

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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = {StaffProfileController.class},
        excludeAutoConfiguration = org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration.class)
@Import({ProfileSecurityConfiguration.class, StaffProblemHandler.class})
class StaffHttpSecurityTest {

    private static MockWebServer authServer;
    private static RSAKey trustedRsaKey;
    private static String issuerUrl;

    @Autowired MockMvc mvc;
    @MockitoBean CurrentUserProfileService profiles;
    @MockitoBean StaffProfileService staffProfileService;

    private static final UUID courierProfileId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID dispatcherProfileId = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID adminProfileId = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private static final String subCourier = "keycloak-sub-courier-01";
    private static final String subDispatcher = "keycloak-sub-dispatcher-01";
    private static final String subAdmin = "keycloak-sub-admin-01";
    private static final String subCustomer = "keycloak-sub-customer-01";
    private static final String subMerchant = "keycloak-sub-merchant-01";

    @BeforeAll
    static void setUpServers() throws Exception {
        trustedRsaKey = new RSAKeyGenerator(2048)
                .keyID("trusted-staff-key")
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

    @Test
    @DisplayName("Anonymous request to /api/v1/staff/me is rejected with 401 Unauthorized ProblemDetail")
    void anonymous_staffMe_rejected() throws Exception {
        mvc.perform(get("/api/v1/staff/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.title").value("Unauthorized"));
    }

    @Test
    @DisplayName("Anonymous request to /api/v1/staff/profile is rejected with 401 Unauthorized ProblemDetail")
    void anonymous_staffProfile_rejected() throws Exception {
        mvc.perform(get("/api/v1/staff/profile"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.title").value("Unauthorized"));
    }

    @Test
    @DisplayName("CUSTOMER role is rejected from /api/v1/staff/me with 403 Forbidden")
    void customerRole_rejected() throws Exception {
        String token = createSignedJwt(subCustomer, List.of("CUSTOMER"));

        mvc.perform(get("/api/v1/staff/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.title").value("Forbidden"));
    }

    @Test
    @DisplayName("MERCHANT role is rejected from /api/v1/staff/me with 403 Forbidden")
    void merchantRole_rejected() throws Exception {
        String token = createSignedJwt(subMerchant, List.of("MERCHANT"));

        mvc.perform(get("/api/v1/staff/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.title").value("Forbidden"));
    }

    @Test
    @DisplayName("COURIER role can read own staff profile on /api/v1/staff/me")
    void courierRole_staffMe_success() throws Exception {
        String token = createSignedJwt(subCourier, List.of("COURIER"));
        Instant now = Instant.parse("2026-10-18T08:30:00Z");

        StaffProfileResponse responseDto = new StaffProfileResponse(
                courierProfileId, "STF-2026-0042", subCourier,
                "Kamal Perera", "courier.kamal@swiftdrop.test", "+94771122334",
                StaffRole.COURIER, "FIELD_LOGISTICS", "ZONE-WEST-COLOMBO",
                StaffStatus.ACTIVE, now, now
        );

        when(staffProfileService.getStaffProfile(any())).thenReturn(responseDto);

        mvc.perform(get("/api/v1/staff/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.profileId").value(courierProfileId.toString()))
                .andExpect(jsonPath("$.id").value(courierProfileId.toString()))
                .andExpect(jsonPath("$.staffId").value("STF-2026-0042"))
                .andExpect(jsonPath("$.keycloakSub").value(subCourier))
                .andExpect(jsonPath("$.role").value("COURIER"))
                .andExpect(jsonPath("$.department").value("FIELD_LOGISTICS"))
                .andExpect(jsonPath("$.assignedZone").value("ZONE-WEST-COLOMBO"))
                .andExpect(jsonPath("$.businessStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.displayName").value("Kamal Perera"))
                .andExpect(jsonPath("$.phoneNumber").value("+94771122334"));
    }

    @Test
    @DisplayName("DISPATCHER role can read own staff profile on /api/v1/staff/profile")
    void dispatcherRole_staffProfile_success() throws Exception {
        String token = createSignedJwt(subDispatcher, List.of("DISPATCHER"));
        Instant now = Instant.parse("2026-10-18T08:30:00Z");

        StaffProfileResponse responseDto = new StaffProfileResponse(
                dispatcherProfileId, "STF-2026-0010", subDispatcher,
                "Nimal Silva", "dispatcher.nimal@swiftdrop.test", "+94772233445",
                StaffRole.DISPATCHER, "DISPATCH_OPERATIONS", null,
                StaffStatus.ACTIVE, now, now
        );

        when(staffProfileService.getStaffProfile(any())).thenReturn(responseDto);

        mvc.perform(get("/api/v1/staff/profile")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.profileId").value(dispatcherProfileId.toString()))
                .andExpect(jsonPath("$.staffId").value("STF-2026-0010"))
                .andExpect(jsonPath("$.role").value("DISPATCHER"))
                .andExpect(jsonPath("$.department").value("DISPATCH_OPERATIONS"))
                .andExpect(jsonPath("$.businessStatus").value("ACTIVE"));
    }

    @Test
    @DisplayName("ADMIN role can read own staff profile on /api/v1/staff/me")
    void adminRole_staffMe_success() throws Exception {
        String token = createSignedJwt(subAdmin, List.of("ADMIN"));
        Instant now = Instant.parse("2026-10-18T08:30:00Z");

        StaffProfileResponse responseDto = new StaffProfileResponse(
                adminProfileId, "STF-2026-0001", subAdmin,
                "John Admin", "admin.john@swiftdrop.test", "+94773344556",
                StaffRole.ADMIN, "PLATFORM_ADMINISTRATION", null,
                StaffStatus.ACTIVE, now, now
        );

        when(staffProfileService.getStaffProfile(any())).thenReturn(responseDto);

        mvc.perform(get("/api/v1/staff/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.profileId").value(adminProfileId.toString()))
                .andExpect(jsonPath("$.staffId").value("STF-2026-0001"))
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.department").value("PLATFORM_ADMINISTRATION"))
                .andExpect(jsonPath("$.businessStatus").value("ACTIVE"));
    }

    @Test
    @DisplayName("Spoofed client headers are ignored; identity is bound strictly to JWT principal")
    void spoofedHeaders_ignored() throws Exception {
        String token = createSignedJwt(subCourier, List.of("COURIER"));
        Instant now = Instant.parse("2026-10-18T08:30:00Z");

        StaffProfileResponse responseDto = new StaffProfileResponse(
                courierProfileId, "STF-2026-0042", subCourier,
                "Kamal Perera", "courier.kamal@swiftdrop.test", "",
                StaffRole.COURIER, "FIELD_LOGISTICS", "ZONE-WEST-COLOMBO",
                StaffStatus.ACTIVE, now, now
        );

        when(staffProfileService.getStaffProfile(any())).thenReturn(responseDto);

        mvc.perform(get("/api/v1/staff/me")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Keycloak-Sub", "attacker-chosen-sub")
                        .header("X-Roles", "ADMIN")
                        .header("X-User-Id", UUID.randomUUID().toString())
                        .param("subject", "attacker-chosen-sub"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keycloakSub").value(subCourier));
    }

    @Test
    @DisplayName("Missing staff profile returns 404 ProblemDetail")
    void missingProfile_returns404() throws Exception {
        String token = createSignedJwt(subCourier, List.of("COURIER"));

        when(staffProfileService.getStaffProfile(any()))
                .thenThrow(new StaffProfileNotFoundException("No staff profile found for subject: " + subCourier));

        mvc.perform(get("/api/v1/staff/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("No staff profile found for subject: " + subCourier));
    }

    @Test
    @DisplayName("Role mismatch returns 403 ProblemDetail")
    void roleMismatch_returns403() throws Exception {
        String token = createSignedJwt(subCourier, List.of("COURIER"));

        when(staffProfileService.getStaffProfile(any()))
                .thenThrow(new StaffRoleMismatchException("Role mismatch: Token does not match profile staff role"));

        mvc.perform(get("/api/v1/staff/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.detail").value("Role mismatch: Token does not match profile staff role"));
    }

    @Test
    @DisplayName("Inactive profile returns 403 ProblemDetail")
    void inactiveProfile_returns403() throws Exception {
        String token = createSignedJwt(subCourier, List.of("COURIER"));

        when(staffProfileService.getStaffProfile(any()))
                .thenThrow(new StaffProfileInactiveException(StaffStatus.SUSPENDED, "Staff profile is SUSPENDED. Operational access is denied."));

        mvc.perform(get("/api/v1/staff/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.detail").value("Staff profile is SUSPENDED. Operational access is denied."));
    }

    private static String createSignedJwt(String subject, List<String> roles) throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuerUrl)
                .audience("swiftdrop-api")
                .subject(subject)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(1, ChronoUnit.HOURS)))
                .notBeforeTime(Date.from(now.minus(1, ChronoUnit.MINUTES)))
                .claim("realm_access", Map.of("roles", roles))
                .claim("roles", roles)
                .claim("email", subject + "@swiftdrop.test")
                .claim("name", "Staff Member " + subject)
                .build();

        SignedJWT signedJWT = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(trustedRsaKey.getKeyID()).build(),
                claims
        );
        signedJWT.sign(new RSASSASigner(trustedRsaKey));
        return signedJWT.serialize();
    }
}
