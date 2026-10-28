package com.swiftdrop.accountsservice.merchant;

import com.swiftdrop.accountsservice.identity.CurrentIdentityProvider;
import com.swiftdrop.accountsservice.identity.TrustedIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class MerchantControllerTest {

    private CurrentIdentityProvider identityProvider;
    private MerchantTenantAuthorizationProvider tenantProvider;
    private MerchantApprovalProvider approvalProvider;
    private MerchantEnrollmentService enrollmentService;
    private com.swiftdrop.accountsservice.reconciliation.MerchantRoleReconciliationService merchantReconciliationService;
    private MerchantRepository merchantRepository;
    private MockMvc mvc;

    private final UUID merchantId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final UUID otherMerchantId = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private final TrustedIdentity merchantIdentity = new TrustedIdentity(
            "keycloak-sub-merchant", "merchant@swiftdrop.test", "Merchant Owner", List.of("MERCHANT")
    );
    private final TrustedIdentity adminIdentity = new TrustedIdentity(
            "keycloak-sub-admin", "admin@swiftdrop.test", "Admin User", List.of("ADMIN")
    );
    private final MerchantRecord merchantRecord = new MerchantRecord(
            merchantId, UUID.randomUUID(), "keycloak-sub-merchant",
            "Store Direct LK", "PV-123456", MerchantStatus.APPROVED,
            Instant.parse("2026-10-15T10:00:00Z")
    );

    @BeforeEach
    void setUp() {
        identityProvider = mock(CurrentIdentityProvider.class);
        tenantProvider = mock(MerchantTenantAuthorizationProvider.class);
        approvalProvider = mock(MerchantApprovalProvider.class);
        enrollmentService = mock(MerchantEnrollmentService.class);
        merchantReconciliationService = mock(com.swiftdrop.accountsservice.reconciliation.MerchantRoleReconciliationService.class);
        merchantRepository = mock(MerchantRepository.class);

        when(identityProvider.currentIdentity()).thenReturn(merchantIdentity);

        var controller = new MerchantController(
                identityProvider, tenantProvider, approvalProvider,
                enrollmentService, merchantReconciliationService, merchantRepository
        );
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new MerchantProblemHandler())
                .build();
    }

    @Test
    @DisplayName("GET /api/v1/merchants/me returns 200 OK and MerchantRecord for bound identity")
    void currentMerchantReturnsOk() throws Exception {
        when(tenantProvider.resolveMerchantForIdentity(merchantIdentity)).thenReturn(Optional.of(merchantRecord));

        var response = mvc.perform(get("/api/v1/merchants/me"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsString();

        var json = JsonMapper.builder().build().readTree(response);
        assertThat(json.get("id").asString()).isEqualTo(merchantId.toString());
        assertThat(json.get("businessName").asString()).isEqualTo("Store Direct LK");
        assertThat(json.get("registrationNumber").asString()).isEqualTo("PV-123456");
        assertThat(json.get("status").asString()).isEqualTo("APPROVED");
        assertThat(json.get("keycloakSub").asString()).isEqualTo("keycloak-sub-merchant");
    }

    @Test
    @DisplayName("GET /api/v1/merchants/me returns 404 ProblemDetail when no merchant profile is bound")
    void currentMerchantReturns404WhenUnbound() throws Exception {
        when(tenantProvider.resolveMerchantForIdentity(merchantIdentity)).thenReturn(Optional.empty());

        mvc.perform(get("/api/v1/merchants/me"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("status").value(404))
                .andExpect(jsonPath("title").value("Not Found"))
                .andExpect(jsonPath("detail").value("No merchant profile bound to current identity"));
    }

    @Test
    @DisplayName("GET /api/v1/merchants/{id} returns 200 OK when caller is authorized for tenant")
    void getMerchantReturnsOkWhenAuthorized() throws Exception {
        doNothing().when(tenantProvider).requireTenantAccess(merchantIdentity, merchantId);
        when(tenantProvider.resolveMerchantById(merchantId)).thenReturn(Optional.of(merchantRecord));

        mvc.perform(get("/api/v1/merchants/{merchantId}", merchantId))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("id").value(merchantId.toString()))
                .andExpect(jsonPath("businessName").value("Store Direct LK"))
                .andExpect(jsonPath("status").value("APPROVED"));
    }

    @Test
    @DisplayName("GET /api/v1/merchants/{id} returns 403 Forbidden ProblemDetail on cross-tenant probe")
    void getMerchantReturns403OnCrossTenantAccess() throws Exception {
        doThrow(new TenantAccessDeniedException("Cross-tenant access denied: Caller does not own merchant tenant " + otherMerchantId))
                .when(tenantProvider).requireTenantAccess(merchantIdentity, otherMerchantId);

        mvc.perform(get("/api/v1/merchants/{merchantId}", otherMerchantId))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("status").value(403))
                .andExpect(jsonPath("title").value("Forbidden"))
                .andExpect(jsonPath("detail").value("Cross-tenant access denied: Caller does not own merchant tenant " + otherMerchantId));
    }

    @Test
    @DisplayName("GET /api/v1/merchants/{id} returns 404 Not Found ProblemDetail when merchant does not exist")
    void getMerchantReturns404WhenNotFound() throws Exception {
        var missingId = UUID.randomUUID();
        doThrow(new MerchantNotFoundException("Merchant not found: " + missingId))
                .when(tenantProvider).requireTenantAccess(merchantIdentity, missingId);

        mvc.perform(get("/api/v1/merchants/{merchantId}", missingId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("status").value(404))
                .andExpect(jsonPath("title").value("Not Found"))
                .andExpect(jsonPath("detail").value("Merchant not found: " + missingId));
    }

    @Test
    @DisplayName("GET /api/v1/merchants/{id}/status returns 200 OK with approval status")
    void getMerchantStatusReturnsOk() throws Exception {
        doNothing().when(tenantProvider).requireTenantAccess(merchantIdentity, merchantId);
        when(approvalProvider.getApprovalStatus(merchantId)).thenReturn(Optional.of(MerchantStatus.APPROVED));

        mvc.perform(get("/api/v1/merchants/{merchantId}/status", merchantId))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("merchantId").value(merchantId.toString()))
                .andExpect(jsonPath("status").value("APPROVED"))
                .andExpect(jsonPath("approved").value(true));
    }

    @Test
    @DisplayName("GET /api/v1/merchants/{id}/status returns approved=false for PENDING merchant")
    void getMerchantStatusPendingReturnsNotApproved() throws Exception {
        doNothing().when(tenantProvider).requireTenantAccess(merchantIdentity, otherMerchantId);
        when(approvalProvider.getApprovalStatus(otherMerchantId)).thenReturn(Optional.of(MerchantStatus.PENDING));

        mvc.perform(get("/api/v1/merchants/{merchantId}/status", otherMerchantId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("status").value("PENDING"))
                .andExpect(jsonPath("approved").value(false));
    }

    @Test
    @DisplayName("POST /api/v1/merchants returns 201 Created and MerchantProfileResponse")
    void registerMerchantReturns201() throws Exception {
        var newMerchantId = UUID.randomUUID();
        var response = new MerchantProfileResponse(
                newMerchantId, "keycloak-sub-merchant",
                "Lanka Retail Holdings Ltd", "PV-109923",
                MerchantStatus.PENDING, Instant.now(), Instant.now()
        );
        when(enrollmentService.enrollMerchant(eq(merchantIdentity), any())).thenReturn(response);

        String requestJson = """
                {
                    "businessName": "Lanka Retail Holdings Ltd",
                    "registrationNumber": "PV-109923"
                }
                """;

        mvc.perform(post("/api/v1/merchants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("id").value(newMerchantId.toString()))
                .andExpect(jsonPath("keycloakSub").value("keycloak-sub-merchant"))
                .andExpect(jsonPath("businessName").value("Lanka Retail Holdings Ltd"))
                .andExpect(jsonPath("registrationNumber").value("PV-109923"))
                .andExpect(jsonPath("status").value("PENDING"));
    }

    @Test
    @DisplayName("POST /api/v1/merchants returns 409 Conflict ProblemDetail on duplicate enrollment")
    void registerMerchantReturns409OnConflict() throws Exception {
        when(enrollmentService.enrollMerchant(eq(merchantIdentity), any()))
                .thenThrow(new MerchantConflictException("Authenticated user already owns a merchant tenant"));

        String requestJson = """
                {
                    "businessName": "Lanka Retail Holdings Ltd",
                    "registrationNumber": "PV-109923"
                }
                """;

        mvc.perform(post("/api/v1/merchants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("status").value(409))
                .andExpect(jsonPath("title").value("Conflict"))
                .andExpect(jsonPath("detail").value("Authenticated user already owns a merchant tenant"));
    }

    @Test
    @DisplayName("POST /api/v1/merchants returns 400 Bad Request on invalid input")
    void registerMerchantReturns400OnInvalidInput() throws Exception {
        String invalidJson = """
                {
                    "businessName": "  ",
                    "registrationNumber": "1"
                }
                """;

        mvc.perform(post("/api/v1/merchants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("status").value(400))
                .andExpect(jsonPath("title").value("Bad Request"));
    }

    @Test
    @DisplayName("POST /api/v1/merchants/{id}/approve returns 200 OK for ADMIN caller")
    void approveMerchantReturns200ForAdmin() throws Exception {
        when(identityProvider.currentIdentity()).thenReturn(adminIdentity);

        var approvedMerchant = new Merchant(
                merchantId, UUID.randomUUID(), "keycloak-sub-merchant",
                "Store Direct LK", "PV-123456", MerchantStatus.APPROVED,
                Instant.now(), Instant.now()
        );
        when(merchantRepository.findById(merchantId)).thenReturn(Optional.of(approvedMerchant));

        String requestJson = """
                {
                    "expectedVersion": 0,
                    "reviewToken": "rvw-123"
                }
                """;

        mvc.perform(post("/api/v1/merchants/{merchantId}/approve", merchantId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("id").value(merchantId.toString()))
                .andExpect(jsonPath("status").value("APPROVED"));

        verify(merchantReconciliationService).approveMerchant(eq(adminIdentity), eq(merchantId), eq(0L), eq("rvw-123"));
    }

    @Test
    @DisplayName("POST /api/v1/merchants/{id}/approve returns 403 Forbidden for non-admin caller")
    void approveMerchantReturns403ForNonAdmin() throws Exception {
        when(identityProvider.currentIdentity()).thenReturn(merchantIdentity);

        mvc.perform(post("/api/v1/merchants/{merchantId}/approve", merchantId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("status").value(403))
                .andExpect(jsonPath("title").value("Forbidden"));

        verifyNoInteractions(merchantReconciliationService);
    }

    @Test
    @DisplayName("POST /api/v1/merchants/{id}/approve returns 409 Conflict on state transition error")
    void approveMerchantReturns409OnStateConflict() throws Exception {
        when(identityProvider.currentIdentity()).thenReturn(adminIdentity);
        doThrow(new MerchantIllegalStateTransitionException("Cannot approve merchant in status: APPROVED"))
                .when(merchantReconciliationService).approveMerchant(any(), eq(merchantId), any(), any());

        mvc.perform(post("/api/v1/merchants/{merchantId}/approve", merchantId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("status").value(409))
                .andExpect(jsonPath("title").value("Conflict"))
                .andExpect(jsonPath("detail").value("Cannot approve merchant in status: APPROVED"));
    }

    @Test
    @DisplayName("POST /api/v1/merchants/{id}/approve returns 409 Conflict on concurrency conflict")
    void approveMerchantReturns409OnConcurrencyConflict() throws Exception {
        when(identityProvider.currentIdentity()).thenReturn(adminIdentity);
        doThrow(new MerchantConcurrencyConflictException("Concurrency conflict on merchant " + merchantId))
                .when(merchantReconciliationService).approveMerchant(any(), eq(merchantId), any(), any());

        mvc.perform(post("/api/v1/merchants/{merchantId}/approve", merchantId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\": 1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("status").value(409))
                .andExpect(jsonPath("title").value("Conflict"))
                .andExpect(jsonPath("detail").value("Concurrency conflict on merchant " + merchantId));
    }

    @Test
    @DisplayName("POST /api/v1/merchants/{id}/suspend returns 200 OK for ADMIN caller with reason")
    void suspendMerchantReturns200ForAdmin() throws Exception {
        when(identityProvider.currentIdentity()).thenReturn(adminIdentity);

        var suspendedMerchant = new Merchant(
                merchantId, UUID.randomUUID(), "keycloak-sub-merchant",
                "Store Direct LK", "PV-123456", MerchantStatus.SUSPENDED,
                Instant.now(), Instant.now()
        );
        when(merchantRepository.findById(merchantId)).thenReturn(Optional.of(suspendedMerchant));

        String requestJson = """
                {
                    "reason": "Policy violation and repeated delivery failures",
                    "expectedVersion": 1,
                    "reviewToken": "rvw-999"
                }
                """;

        mvc.perform(post("/api/v1/merchants/{merchantId}/suspend", merchantId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("id").value(merchantId.toString()))
                .andExpect(jsonPath("status").value("SUSPENDED"));

        verify(merchantReconciliationService).suspendMerchant(
                eq(adminIdentity), eq(merchantId), eq("Policy violation and repeated delivery failures"), eq(1L), eq("rvw-999")
        );
    }

    @Test
    @DisplayName("POST /api/v1/merchants/{id}/suspend returns 400 Bad Request when reason is missing or blank")
    void suspendMerchantReturns400WhenReasonMissing() throws Exception {
        when(identityProvider.currentIdentity()).thenReturn(adminIdentity);

        String invalidJson = """
                {
                    "reason": "   "
                }
                """;

        mvc.perform(post("/api/v1/merchants/{merchantId}/suspend", merchantId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("status").value(400))
                .andExpect(jsonPath("title").value("Bad Request"))
                .andExpect(jsonPath("detail").value("Suspension requires a non-empty reason."));

        verifyNoInteractions(merchantReconciliationService);
    }

    @Test
    @DisplayName("POST /api/v1/merchants/{id}/suspend returns 403 Forbidden for non-admin caller")
    void suspendMerchantReturns403ForNonAdmin() throws Exception {
        when(identityProvider.currentIdentity()).thenReturn(merchantIdentity);

        String validJson = """
                {
                    "reason": "Valid reason"
                }
                """;

        mvc.perform(post("/api/v1/merchants/{merchantId}/suspend", merchantId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validJson))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("status").value(403))
                .andExpect(jsonPath("title").value("Forbidden"));

        verifyNoInteractions(merchantReconciliationService);
    }
}
