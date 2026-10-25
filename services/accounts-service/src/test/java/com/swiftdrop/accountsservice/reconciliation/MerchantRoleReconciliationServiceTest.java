package com.swiftdrop.accountsservice.reconciliation;

import com.swiftdrop.accountsservice.identity.TrustedIdentity;
import com.swiftdrop.accountsservice.identity.keycloak.KeycloakIdentityAdapter;
import com.swiftdrop.accountsservice.identity.keycloak.ReconciliationOutcome;
import com.swiftdrop.accountsservice.identity.keycloak.ReconciliationResult;
import com.swiftdrop.accountsservice.merchant.Merchant;
import com.swiftdrop.accountsservice.merchant.MerchantNotFoundException;
import com.swiftdrop.accountsservice.merchant.MerchantRepository;
import com.swiftdrop.accountsservice.merchant.MerchantStatus;
import com.swiftdrop.accountsservice.staff.StaffAccessDeniedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class MerchantRoleReconciliationServiceTest {

    private MerchantRepository merchantRepository;
    private KeycloakIdentityAdapter identityAdapter;
    private ReconciliationAuditService auditService;
    private MerchantRoleReconciliationService service;

    private final String adminSub = "admin-sub-001";
    private final String merchantSub = "merchant-sub-001";
    private final UUID merchantId = UUID.fromString("99999999-9999-9999-9999-999999999999");
    private final UUID accountProfileId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    private final TrustedIdentity adminIdentity = new TrustedIdentity(
            adminSub, "admin@swiftdrop.test", "Admin User", List.of("ADMIN")
    );
    private final TrustedIdentity customerIdentity = new TrustedIdentity(
            merchantSub, "merchant@swiftdrop.test", "Merchant Owner", List.of("CUSTOMER")
    );

    @BeforeEach
    void setUp() {
        merchantRepository = mock(MerchantRepository.class);
        identityAdapter = mock(KeycloakIdentityAdapter.class);
        auditService = mock(ReconciliationAuditService.class);
        service = new MerchantRoleReconciliationService(
                merchantRepository, identityAdapter, auditService, "ON_APPROVAL"
        );
    }

    @Test
    @DisplayName("handleEnrollment under ON_APPROVAL policy does not grant MERCHANT role (safe no-op)")
    void handleEnrollment_underOnApprovalPolicy_isNoOp() {
        ReconciliationResult result = service.handleEnrollment(merchantSub);

        assertThat(result.outcome()).isEqualTo(ReconciliationOutcome.NO_OP);
        assertThat(result.message()).contains("ON_APPROVAL policy");
        verifyNoInteractions(identityAdapter);
    }

    @Test
    @DisplayName("handleEnrollment under ON_ENROLLMENT policy provisionally triggers role grant")
    void handleEnrollment_underOnEnrollmentPolicy_grantsRole() {
        MerchantRoleReconciliationService enrollmentService = new MerchantRoleReconciliationService(
                merchantRepository, identityAdapter, auditService, "ON_ENROLLMENT"
        );

        when(identityAdapter.reconcileRole(merchantSub, "MERCHANT", true))
                .thenReturn(ReconciliationResult.success(merchantSub, "MERCHANT", "Role granted"));

        ReconciliationResult result = enrollmentService.handleEnrollment(merchantSub);

        assertThat(result.outcome()).isEqualTo(ReconciliationOutcome.SUCCESS);
        verify(identityAdapter).reconcileRole(merchantSub, "MERCHANT", true);
    }

    @Test
    @DisplayName("approveMerchant updates status to APPROVED and grants Keycloak MERCHANT role")
    void approveMerchant_updatesStatusAndGrantsRole() {
        Merchant merchant = new Merchant(
                merchantId, accountProfileId, merchantSub, "Apex Logistics Ltd",
                "REG-2026-001", MerchantStatus.PENDING, Instant.now(), Instant.now()
        );

        when(merchantRepository.findById(merchantId)).thenReturn(Optional.of(merchant));
        when(identityAdapter.reconcileRole(merchantSub, "MERCHANT", true))
                .thenReturn(ReconciliationResult.success(merchantSub, "MERCHANT", "Role granted"));

        ReconciliationResult result = service.approveMerchant(adminIdentity, merchantId);

        assertThat(result.outcome()).isEqualTo(ReconciliationOutcome.SUCCESS);
        assertThat(merchant.getStatus()).isEqualTo(MerchantStatus.APPROVED);
        verify(merchantRepository).save(merchant);
        verify(identityAdapter).reconcileRole(merchantSub, "MERCHANT", true);
        verify(auditService).recordAudit(eq(merchantSub), eq("MERCHANT"), eq("APPROVE_MERCHANT"),
                eq("PENDING"), eq("APPROVED"), eq(ReconciliationOutcome.SUCCESS), eq(adminSub), any(), any());
    }

    @Test
    @DisplayName("suspendMerchant updates status to SUSPENDED and revokes Keycloak MERCHANT role")
    void suspendMerchant_updatesStatusAndRevokesRole() {
        Merchant merchant = new Merchant(
                merchantId, accountProfileId, merchantSub, "Apex Logistics Ltd",
                "REG-2026-001", MerchantStatus.APPROVED, Instant.now(), Instant.now()
        );

        when(merchantRepository.findById(merchantId)).thenReturn(Optional.of(merchant));
        when(identityAdapter.reconcileRole(merchantSub, "MERCHANT", false))
                .thenReturn(ReconciliationResult.success(merchantSub, "MERCHANT", "Role revoked"));

        ReconciliationResult result = service.suspendMerchant(adminIdentity, merchantId, "Compliance review");

        assertThat(result.outcome()).isEqualTo(ReconciliationOutcome.SUCCESS);
        assertThat(merchant.getStatus()).isEqualTo(MerchantStatus.SUSPENDED);
        verify(merchantRepository).save(merchant);
        verify(identityAdapter).reconcileRole(merchantSub, "MERCHANT", false);
    }

    @Test
    @DisplayName("stale retry protection: reconciling SUSPENDED merchant revokes role, never re-grants")
    void reconcileMerchantRole_withSuspendedStatus_revokesRole() {
        Merchant merchant = new Merchant(
                merchantId, accountProfileId, merchantSub, "Apex Logistics Ltd",
                "REG-2026-001", MerchantStatus.SUSPENDED, Instant.now(), Instant.now()
        );

        when(merchantRepository.findByKeycloakSub(merchantSub)).thenReturn(Optional.of(merchant));
        when(identityAdapter.reconcileRole(merchantSub, "MERCHANT", false))
                .thenReturn(ReconciliationResult.noOp(merchantSub, "MERCHANT", "Already absent"));

        ReconciliationResult result = service.reconcileMerchantRole(adminIdentity, merchantSub);

        assertThat(result.outcome()).isEqualTo(ReconciliationOutcome.NO_OP);
        // Desired role is false because status is SUSPENDED!
        verify(identityAdapter).reconcileRole(merchantSub, "MERCHANT", false);
    }

    @Test
    @DisplayName("approveMerchant fails closed when non-admin attempts operation")
    void approveMerchant_failsClosed_forNonAdmin() {
        assertThatThrownBy(() -> service.approveMerchant(customerIdentity, merchantId))
                .isInstanceOf(StaffAccessDeniedException.class)
                .hasMessageContaining("requires authenticated ADMIN role");

        verifyNoInteractions(merchantRepository, identityAdapter);
    }
}
