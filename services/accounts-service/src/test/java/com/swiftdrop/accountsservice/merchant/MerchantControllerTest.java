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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class MerchantControllerTest {

    private CurrentIdentityProvider identityProvider;
    private MerchantTenantAuthorizationProvider tenantProvider;
    private MerchantApprovalProvider approvalProvider;
    private MockMvc mvc;

    private final UUID merchantId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final UUID otherMerchantId = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private final TrustedIdentity merchantIdentity = new TrustedIdentity(
            "keycloak-sub-merchant", "merchant@swiftdrop.test", "Merchant Owner", List.of("MERCHANT")
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

        when(identityProvider.currentIdentity()).thenReturn(merchantIdentity);

        var controller = new MerchantController(identityProvider, tenantProvider, approvalProvider);
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
}
