package com.swiftdrop.accountsservice.reconciliation;

import com.swiftdrop.accountsservice.identity.TrustedIdentity;
import com.swiftdrop.accountsservice.identity.keycloak.KeycloakAdminUnavailableException;
import com.swiftdrop.accountsservice.identity.keycloak.KeycloakIdentityAdapter;
import com.swiftdrop.accountsservice.identity.keycloak.KeycloakRoleAllowlist;
import com.swiftdrop.accountsservice.identity.keycloak.ReconciliationOutcome;
import com.swiftdrop.accountsservice.identity.keycloak.ReconciliationResult;
import com.swiftdrop.accountsservice.merchant.Merchant;
import com.swiftdrop.accountsservice.merchant.MerchantRepository;
import com.swiftdrop.accountsservice.merchant.MerchantStatus;
import com.swiftdrop.accountsservice.staff.StaffProfile;
import com.swiftdrop.accountsservice.staff.StaffProfileRepository;
import com.swiftdrop.accountsservice.staff.StaffRole;
import com.swiftdrop.accountsservice.staff.StaffStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReconciliationRecoveryServiceTest {

    @Mock
    private ReconciliationAuditService auditService;
    @Mock
    private StaffRoleReconciliationService staffReconciliationService;
    @Mock
    private MerchantRoleReconciliationService merchantReconciliationService;
    @Mock
    private StaffProfileRepository staffRepository;
    @Mock
    private MerchantRepository merchantRepository;
    @Mock
    private KeycloakIdentityAdapter keycloakAdapter;

    private ReconciliationRecoveryService recoveryService;

    private final TrustedIdentity adminIdentity = new TrustedIdentity(
            "admin-sub-001", "admin@swiftdrop.com", "Admin User",
            List.of(KeycloakRoleAllowlist.ADMIN)
    );
    private final TrustedIdentity customerIdentity = new TrustedIdentity(
            "customer-sub-002", "cust@swiftdrop.com", "Customer User",
            List.of(KeycloakRoleAllowlist.CUSTOMER)
    );

    @BeforeEach
    void setUp() {
        recoveryService = new ReconciliationRecoveryService(
                auditService,
                staffReconciliationService,
                merchantReconciliationService,
                staffRepository,
                merchantRepository,
                keycloakAdapter
        );
    }

    @Test
    @DisplayName("Non-ADMIN actor is denied from triggering reconciliation recovery")
    void nonAdminIsDenied() {
        UUID auditId = UUID.randomUUID();
        assertThatThrownBy(() -> recoveryService.retryReconciliation(auditId, customerIdentity))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Only ADMIN actors may retry identity reconciliation");
    }

    @Test
    @DisplayName("Throws IllegalArgumentException if audit record does not exist")
    void nonExistentAuditRecordThrows() {
        UUID auditId = UUID.randomUUID();
        when(auditService.findById(auditId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> recoveryService.retryReconciliation(auditId, adminIdentity))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Reconciliation audit record not found");
    }

    @Test
    @DisplayName("Active staff member role is successfully synchronized during recovery")
    void recoverActiveStaffSuccessfully() {
        UUID auditId = UUID.randomUUID();
        String sub = "staff-sub-123";
        IdentityReconciliationAudit audit = new IdentityReconciliationAudit(
                auditId, sub, "COURIER", "PROVISION_STAFF", "NONE", "PENDING_RETRY",
                ReconciliationOutcome.PENDING_RETRY, "admin-sub-001", null, 1,
                "Network timeout", Instant.now(), Instant.now()
        );
        when(auditService.findById(auditId)).thenReturn(Optional.of(audit));

        StaffProfile profile = new StaffProfile(
                UUID.randomUUID(), UUID.randomUUID(), sub, "STF-100",
                StaffRole.COURIER, "LOGISTICS", "ZONE-A", StaffStatus.ACTIVE,
                Instant.now(), Instant.now()
        );
        when(staffRepository.findByKeycloakSub(sub)).thenReturn(Optional.of(profile));
        when(staffReconciliationService.syncStaffRole(adminIdentity, sub))
                .thenReturn(ReconciliationResult.success(sub, "COURIER", "Synchronized"));

        ReconciliationResult result = recoveryService.retryReconciliation(auditId, adminIdentity);

        assertThat(result.outcome()).isEqualTo(ReconciliationOutcome.SUCCESS);
        verify(auditService).updateAuditOutcome(eq(auditId), eq(ReconciliationOutcome.SUCCESS), eq(2), eq("ACTIVE"), anyString());
    }

    @Test
    @DisplayName("SECURITY: Stale retry for suspended staff revokes Keycloak role and never restores privilege")
    void recoverSuspendedStaffEnforcesRevocation() {
        UUID auditId = UUID.randomUUID();
        String sub = "staff-sub-suspended";
        IdentityReconciliationAudit audit = new IdentityReconciliationAudit(
                auditId, sub, "COURIER", "PROVISION_STAFF", "NONE", "PENDING_RETRY",
                ReconciliationOutcome.PENDING_RETRY, "admin-sub-001", null, 1,
                "Earlier failure", Instant.now(), Instant.now()
        );
        when(auditService.findById(auditId)).thenReturn(Optional.of(audit));

        StaffProfile profile = new StaffProfile(
                UUID.randomUUID(), UUID.randomUUID(), sub, "STF-100",
                StaffRole.COURIER, "LOGISTICS", "ZONE-A", StaffStatus.SUSPENDED,
                Instant.now(), Instant.now()
        );
        when(staffRepository.findByKeycloakSub(sub)).thenReturn(Optional.of(profile));

        ReconciliationResult result = recoveryService.retryReconciliation(auditId, adminIdentity);

        assertThat(result.outcome()).isEqualTo(ReconciliationOutcome.SUCCESS);
        assertThat(result.message()).contains("SUSPENDED");
        verify(keycloakAdapter).revokeRole(sub, "COURIER");
        verify(keycloakAdapter, never()).grantRole(anyString(), anyString());
        verify(auditService).updateAuditOutcome(eq(auditId), eq(ReconciliationOutcome.SUCCESS), eq(2), eq("SUSPENDED"), anyString());
    }

    @Test
    @DisplayName("SECURITY: Stale retry for suspended merchant revokes MERCHANT role and never restores privilege")
    void recoverSuspendedMerchantEnforcesRevocation() {
        UUID auditId = UUID.randomUUID();
        String sub = "merchant-sub-suspended";
        IdentityReconciliationAudit audit = new IdentityReconciliationAudit(
                auditId, sub, "MERCHANT", "APPROVE_MERCHANT", "PENDING", "PENDING_RETRY",
                ReconciliationOutcome.PENDING_RETRY, "admin-sub-001", null, 1,
                "Earlier failure", Instant.now(), Instant.now()
        );
        when(auditService.findById(auditId)).thenReturn(Optional.of(audit));

        Merchant merchant = new Merchant(
                UUID.randomUUID(), UUID.randomUUID(), sub, "MERCH-999",
                "Acme Corp", MerchantStatus.SUSPENDED, Instant.now(), Instant.now()
        );
        when(staffRepository.findByKeycloakSub(sub)).thenReturn(Optional.empty());
        when(merchantRepository.findByKeycloakSub(sub)).thenReturn(Optional.of(merchant));

        ReconciliationResult result = recoveryService.retryReconciliation(auditId, adminIdentity);

        assertThat(result.outcome()).isEqualTo(ReconciliationOutcome.SUCCESS);
        assertThat(result.message()).contains("SUSPENDED");
        verify(keycloakAdapter).revokeRole(sub, KeycloakRoleAllowlist.MERCHANT);
        verify(keycloakAdapter, never()).grantRole(anyString(), anyString());
        verify(auditService).updateAuditOutcome(eq(auditId), eq(ReconciliationOutcome.SUCCESS), eq(2), eq("SUSPENDED"), anyString());
    }

    @Test
    @DisplayName("Fail-closed: Orphan subject with no Accounts record revokes stale Keycloak role and denies")
    void recoverOrphanSubjectFailsClosed() {
        UUID auditId = UUID.randomUUID();
        String sub = "orphan-sub-000";
        IdentityReconciliationAudit audit = new IdentityReconciliationAudit(
                auditId, sub, "COURIER", "GRANT_ROLE", "NONE", "FAILED",
                ReconciliationOutcome.FAILED, "admin-sub-001", null, 1,
                "Earlier error", Instant.now(), Instant.now()
        );
        when(auditService.findById(auditId)).thenReturn(Optional.of(audit));
        when(staffRepository.findByKeycloakSub(sub)).thenReturn(Optional.empty());
        when(merchantRepository.findByKeycloakSub(sub)).thenReturn(Optional.empty());

        ReconciliationResult result = recoveryService.retryReconciliation(auditId, adminIdentity);

        assertThat(result.outcome()).isEqualTo(ReconciliationOutcome.DENIED);
        verify(keycloakAdapter).revokeRole(sub, "COURIER");
        verify(auditService).updateAuditOutcome(eq(auditId), eq(ReconciliationOutcome.DENIED), eq(2), eq("REVOKED_ORPHAN"), anyString());
    }

    @Test
    @DisplayName("Keycloak unavailable during recovery sets PENDING_RETRY in audit and propagates exception")
    void keycloakUnavailablePropagates() {
        UUID auditId = UUID.randomUUID();
        String sub = "staff-sub-unavail";
        IdentityReconciliationAudit audit = new IdentityReconciliationAudit(
                auditId, sub, "COURIER", "PROVISION_STAFF", "NONE", "PENDING_RETRY",
                ReconciliationOutcome.PENDING_RETRY, "admin-sub-001", null, 1,
                "Earlier error", Instant.now(), Instant.now()
        );
        when(auditService.findById(auditId)).thenReturn(Optional.of(audit));

        StaffProfile profile = new StaffProfile(
                UUID.randomUUID(), UUID.randomUUID(), sub, "STF-100",
                StaffRole.COURIER, "LOGISTICS", "ZONE-A", StaffStatus.ACTIVE,
                Instant.now(), Instant.now()
        );
        when(staffRepository.findByKeycloakSub(sub)).thenReturn(Optional.of(profile));
        when(staffReconciliationService.syncStaffRole(adminIdentity, sub))
                .thenThrow(new KeycloakAdminUnavailableException("Keycloak connection timeout"));

        assertThatThrownBy(() -> recoveryService.retryReconciliation(auditId, adminIdentity))
                .isInstanceOf(KeycloakAdminUnavailableException.class);

        verify(auditService).updateAuditOutcome(eq(auditId), eq(ReconciliationOutcome.PENDING_RETRY), eq(2), eq("UNAVAILABLE"), anyString());
    }
}
