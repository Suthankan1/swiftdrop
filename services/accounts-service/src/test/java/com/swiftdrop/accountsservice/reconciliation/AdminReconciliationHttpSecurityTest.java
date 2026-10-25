package com.swiftdrop.accountsservice.reconciliation;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.swiftdrop.accountsservice.identity.keycloak.IdentityNotFoundException;
import com.swiftdrop.accountsservice.identity.keycloak.KeycloakAdminUnavailableException;
import com.swiftdrop.accountsservice.identity.keycloak.KeycloakRoleAllowlist;
import com.swiftdrop.accountsservice.identity.keycloak.ReconciliationResult;
import com.swiftdrop.accountsservice.security.ProfileSecurityConfiguration;
import com.swiftdrop.accountsservice.staff.StaffProfileResponse;
import com.swiftdrop.accountsservice.staff.StaffRole;
import com.swiftdrop.accountsservice.staff.StaffStatus;
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
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {AdminReconciliationController.class},
        excludeAutoConfiguration = org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration.class)
@Import({ProfileSecurityConfiguration.class, ReconciliationProblemHandler.class})
class AdminReconciliationHttpSecurityTest {

    private static MockWebServer authServer;
    private static RSAKey trustedRsaKey;
    private static RSAKey untrustedRsaKey;
    private static String issuerUrl;

    @Autowired
    MockMvc mvc;

    @MockitoBean
    StaffRoleReconciliationService staffReconciliationService;
    @MockitoBean
    MerchantRoleReconciliationService merchantReconciliationService;
    @MockitoBean
    ReconciliationRecoveryService recoveryService;
    @MockitoBean
    ReconciliationAuditService auditService;

    private static final String subAdmin = "admin-sub-001";
    private static final String subCustomer = "cust-sub-002";
    private static final String subCourier = "courier-sub-003";
    private static final String subDispatcher = "dispatcher-sub-004";
    private static final String subMerchant = "merchant-sub-005";

    @BeforeAll
    static void setUpServers() throws Exception {
        trustedRsaKey = new RSAKeyGenerator(2048)
                .keyID("trusted-reconcile-key")
                .generate();

        untrustedRsaKey = new RSAKeyGenerator(2048)
                .keyID("untrusted-forged-key")
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
    @DisplayName("Anonymous request to /api/v1/admin/reconciliation/staff/provision is rejected with 401")
    void anonymous_provision_rejected() throws Exception {
        mvc.perform(post("/api/v1/admin/reconciliation/staff/provision")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keycloakSub\":\"sub-123\",\"staffId\":\"STF-100\",\"role\":\"COURIER\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.title").value("Unauthorized"));
    }

    @Test
    @DisplayName("Anonymous request to /api/v1/admin/reconciliation/audit/{sub} is rejected with 401")
    void anonymous_audit_rejected() throws Exception {
        mvc.perform(get("/api/v1/admin/reconciliation/audit/sub-123"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.title").value("Unauthorized"));
    }

    @Test
    @DisplayName("CUSTOMER role is rejected from admin reconciliation with 403 Forbidden")
    void customer_rejected() throws Exception {
        String token = createSignedJwt(subCustomer, List.of(KeycloakRoleAllowlist.CUSTOMER), trustedRsaKey);

        mvc.perform(post("/api/v1/admin/reconciliation/staff/sync/target-123")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.title").value("Forbidden"));
    }

    @Test
    @DisplayName("COURIER role is rejected from admin reconciliation with 403 Forbidden")
    void courier_rejected() throws Exception {
        String token = createSignedJwt(subCourier, List.of(KeycloakRoleAllowlist.COURIER), trustedRsaKey);

        mvc.perform(post("/api/v1/admin/reconciliation/staff/target-123/suspend")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @DisplayName("DISPATCHER role is rejected from admin reconciliation with 403 Forbidden")
    void dispatcher_rejected() throws Exception {
        String token = createSignedJwt(subDispatcher, List.of(KeycloakRoleAllowlist.DISPATCHER), trustedRsaKey);

        mvc.perform(get("/api/v1/admin/reconciliation/audit/target-123")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @DisplayName("MERCHANT role is rejected from admin reconciliation with 403 Forbidden")
    void merchant_rejected() throws Exception {
        String token = createSignedJwt(subMerchant, List.of(KeycloakRoleAllowlist.MERCHANT), trustedRsaKey);

        mvc.perform(post("/api/v1/admin/reconciliation/merchant/target-123/sync")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @DisplayName("Forged JWT signed by untrusted key is rejected with 401 Unauthorized")
    void forgedToken_rejected() throws Exception {
        String forgedToken = createSignedJwt(subAdmin, List.of(KeycloakRoleAllowlist.ADMIN), untrustedRsaKey);

        mvc.perform(get("/api/v1/admin/reconciliation/audit/target-123")
                        .header("Authorization", "Bearer " + forgedToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("Authenticated ADMIN successfully provisions staff profile and role")
    void admin_provisionStaff_success() throws Exception {
        String token = createSignedJwt(subAdmin, List.of(KeycloakRoleAllowlist.ADMIN), trustedRsaKey);

        StaffProfileResponse response = new StaffProfileResponse(
                UUID.randomUUID(), "STF-100", "courier-sub-1", "Courier One",
                "courier@swiftdrop.com", "+94771234567", StaffRole.COURIER,
                "LOGISTICS", "ZONE-A", StaffStatus.ACTIVE, Instant.now(), Instant.now()
        );
        when(staffReconciliationService.provisionStaff(any(), any())).thenReturn(response);

        String json = """
                {
                    "keycloakSub": "courier-sub-1",
                    "staffId": "STF-100",
                    "role": "COURIER",
                    "department": "LOGISTICS",
                    "assignedZone": "ZONE-A"
                }
                """;

        mvc.perform(post("/api/v1/admin/reconciliation/staff/provision")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.staffId").value("STF-100"))
                .andExpect(jsonPath("$.role").value("COURIER"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    @DisplayName("Authenticated ADMIN successfully syncs staff role")
    void admin_syncStaffRole_success() throws Exception {
        String token = createSignedJwt(subAdmin, List.of(KeycloakRoleAllowlist.ADMIN), trustedRsaKey);
        String targetSub = "target-staff-sub";

        when(staffReconciliationService.syncStaffRole(any(), eq(targetSub)))
                .thenReturn(ReconciliationResult.success(targetSub, "COURIER", "Role synchronized"));

        mvc.perform(post("/api/v1/admin/reconciliation/staff/" + targetSub + "/sync")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("SUCCESS"))
                .andExpect(jsonPath("$.roleName").value("COURIER"));
    }

    @Test
    @DisplayName("Authenticated ADMIN successfully suspends staff profile and revokes role")
    void admin_suspendStaff_success() throws Exception {
        String token = createSignedJwt(subAdmin, List.of(KeycloakRoleAllowlist.ADMIN), trustedRsaKey);
        String targetSub = "target-staff-sub";

        when(staffReconciliationService.suspendStaff(any(), eq(targetSub), any()))
                .thenReturn(ReconciliationResult.success(targetSub, "COURIER", "Suspended and role revoked"));

        mvc.perform(post("/api/v1/admin/reconciliation/staff/" + targetSub + "/suspend")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("SUCCESS"));
    }

    @Test
    @DisplayName("Authenticated ADMIN successfully retries failed reconciliation")
    void admin_retry_success() throws Exception {
        String token = createSignedJwt(subAdmin, List.of(KeycloakRoleAllowlist.ADMIN), trustedRsaKey);
        UUID auditId = UUID.randomUUID();

        when(recoveryService.retryReconciliation(eq(auditId), any()))
                .thenReturn(ReconciliationResult.success("target-sub", "MERCHANT", "Reconciliation completed"));

        mvc.perform(post("/api/v1/admin/reconciliation/retry/" + auditId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("SUCCESS"));
    }

    @Test
    @DisplayName("Keycloak unavailable during admin operation returns 503 ProblemDetail")
    void keycloakUnavailable_returns503ProblemDetail() throws Exception {
        String token = createSignedJwt(subAdmin, List.of(KeycloakRoleAllowlist.ADMIN), trustedRsaKey);
        String targetSub = "target-staff-sub";

        when(staffReconciliationService.syncStaffRole(any(), eq(targetSub)))
                .thenThrow(new KeycloakAdminUnavailableException("Keycloak connection refused"));

        mvc.perform(post("/api/v1/admin/reconciliation/staff/" + targetSub + "/sync")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.detail").value("Keycloak connection refused"));
    }

    @Test
    @DisplayName("Target identity not found returns 404 ProblemDetail")
    void identityNotFound_returns404ProblemDetail() throws Exception {
        String token = createSignedJwt(subAdmin, List.of(KeycloakRoleAllowlist.ADMIN), trustedRsaKey);
        String targetSub = "missing-sub";

        when(staffReconciliationService.syncStaffRole(any(), eq(targetSub)))
                .thenThrow(new IdentityNotFoundException(targetSub));

        mvc.perform(post("/api/v1/admin/reconciliation/staff/" + targetSub + "/sync")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    private static String createSignedJwt(String subject, List<String> roles, RSAKey key) throws Exception {
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
                .claim("name", "User " + subject)
                .build();

        SignedJWT signedJWT = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
                claims
        );
        signedJWT.sign(new RSASSASigner(key));
        return signedJWT.serialize();
    }
}
