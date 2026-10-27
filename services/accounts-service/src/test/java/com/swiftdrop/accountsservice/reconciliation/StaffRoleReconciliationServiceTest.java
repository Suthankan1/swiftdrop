package com.swiftdrop.accountsservice.reconciliation;

import com.swiftdrop.accountsservice.identity.TrustedIdentity;
import com.swiftdrop.accountsservice.identity.keycloak.IdentityNotFoundException;
import com.swiftdrop.accountsservice.identity.keycloak.KeycloakIdentityAdapter;
import com.swiftdrop.accountsservice.identity.keycloak.ReconciliationOutcome;
import com.swiftdrop.accountsservice.identity.keycloak.ReconciliationResult;
import com.swiftdrop.accountsservice.identity.keycloak.RoleMutationResult;
import com.swiftdrop.accountsservice.staff.ProvisionStaffCommand;
import com.swiftdrop.accountsservice.staff.StaffAccessDeniedException;
import com.swiftdrop.accountsservice.staff.StaffProfile;
import com.swiftdrop.accountsservice.staff.StaffProfileRepository;
import com.swiftdrop.accountsservice.staff.StaffProfileResponse;
import com.swiftdrop.accountsservice.staff.StaffProvisioningException;
import com.swiftdrop.accountsservice.staff.StaffRole;
import com.swiftdrop.accountsservice.staff.StaffStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class StaffRoleReconciliationServiceTest {

    private StaffProfileRepository staffProfiles;
    private KeycloakIdentityAdapter identityAdapter;
    private ReconciliationAuditService auditService;
    private StaffRoleReconciliationService service;

    private final String adminSub = "admin-sub-001";
    private final String courierSub = "courier-sub-001";
    private final TrustedIdentity adminIdentity = new TrustedIdentity(
            adminSub, "admin@swiftdrop.test", "Admin User", List.of("ADMIN")
    );
    private final TrustedIdentity nonAdminIdentity = new TrustedIdentity(
            "customer-sub-001", "cust@swiftdrop.test", "Customer", List.of("CUSTOMER")
    );

    @BeforeEach
    void setUp() {
        staffProfiles = mock(StaffProfileRepository.class);
        identityAdapter = mock(KeycloakIdentityAdapter.class);
        auditService = mock(ReconciliationAuditService.class);
        service = new StaffRoleReconciliationService(staffProfiles, identityAdapter, auditService);
    }

    @Test
    @DisplayName("provisionStaff succeeds with valid ADMIN authority and reconciles Keycloak role")
    void provisionStaff_succeedsWithAdmin() {
        ProvisionStaffCommand command = new ProvisionStaffCommand(
                courierSub, "STF-2026-0042", StaffRole.COURIER, "FIELD_LOGISTICS", "ZONE-1", null
        );

        when(identityAdapter.identityExists(courierSub)).thenReturn(true);
        when(staffProfiles.findByKeycloakSub(courierSub)).thenReturn(Optional.empty());
        when(staffProfiles.existsByStaffId("STF-2026-0042")).thenReturn(false);
        when(staffProfiles.save(any(StaffProfile.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(identityAdapter.grantRole(courierSub, "COURIER"))
                .thenReturn(new RoleMutationResult(true, "COURIER", Set.of("COURIER")));

        StaffProfileResponse response = service.provisionStaff(adminIdentity, command);

        assertThat(response.keycloakSub()).isEqualTo(courierSub);
        assertThat(response.staffId()).isEqualTo("STF-2026-0042");
        assertThat(response.role()).isEqualTo(StaffRole.COURIER);
        assertThat(response.status()).isEqualTo(StaffStatus.ACTIVE);

        verify(identityAdapter).grantRole(courierSub, "COURIER");
        verify(auditService).recordAudit(eq(courierSub), eq("COURIER"), eq("PROVISION_STAFF"),
                eq("NONE"), eq("ACTIVE"), eq(ReconciliationOutcome.SUCCESS), eq(adminSub), any(), any());
    }

    @Test
    @DisplayName("provisionStaff fails closed when caller is not an ADMIN")
    void provisionStaff_failsClosed_whenCallerNotAdmin() {
        ProvisionStaffCommand command = new ProvisionStaffCommand(
                courierSub, "STF-2026-0042", StaffRole.COURIER, "FIELD_LOGISTICS", "ZONE-1", null
        );

        assertThatThrownBy(() -> service.provisionStaff(nonAdminIdentity, command))
                .isInstanceOf(StaffAccessDeniedException.class)
                .hasMessageContaining("requires authenticated ADMIN role");

        assertThatThrownBy(() -> service.provisionStaff(null, command))
                .isInstanceOf(StaffAccessDeniedException.class);

        verifyNoInteractions(identityAdapter, staffProfiles);
    }

    @Test
    @DisplayName("provisionStaff fails closed when target identity does not exist in Keycloak")
    void provisionStaff_failsClosed_whenIdentityNotFound() {
        ProvisionStaffCommand command = new ProvisionStaffCommand(
                "missing-sub", "STF-2026-0042", StaffRole.COURIER, "FIELD_LOGISTICS", "ZONE-1", null
        );

        when(identityAdapter.identityExists("missing-sub")).thenReturn(false);

        assertThatThrownBy(() -> service.provisionStaff(adminIdentity, command))
                .isInstanceOf(IdentityNotFoundException.class);

        verify(staffProfiles, never()).save(any());
        verify(auditService).recordAudit(eq("missing-sub"), eq("COURIER"), eq("PROVISION_STAFF"),
                eq("NONE"), eq("FAILED"), eq(ReconciliationOutcome.FAILED), eq(adminSub), any(), any());
    }

    @Test
    @DisplayName("provisionStaff handles duplicate provisioning idempotently when attributes match")
    void provisionStaff_isIdempotent_whenMatchingProfileExists() {
        ProvisionStaffCommand command = new ProvisionStaffCommand(
                courierSub, "STF-2026-0042", StaffRole.COURIER, "FIELD_LOGISTICS", "ZONE-1", null
        );

        StaffProfile existing = new StaffProfile(
                UUID.randomUUID(), null, courierSub, "STF-2026-0042", StaffRole.COURIER,
                "FIELD_LOGISTICS", "ZONE-1", StaffStatus.ACTIVE, Instant.now(), Instant.now()
        );

        when(identityAdapter.identityExists(courierSub)).thenReturn(true);
        when(staffProfiles.findByKeycloakSub(courierSub)).thenReturn(Optional.of(existing));
        when(identityAdapter.getUserRoles(courierSub)).thenReturn(Set.of("COURIER"));

        StaffProfileResponse response = service.provisionStaff(adminIdentity, command);

        assertThat(response.keycloakSub()).isEqualTo(courierSub);
        verify(identityAdapter, never()).grantRole(any(), any());
        verify(auditService).recordAudit(eq(courierSub), eq("COURIER"), eq("PROVISION_STAFF"),
                eq("ACTIVE"), eq("ACTIVE"), eq(ReconciliationOutcome.NO_OP), eq(adminSub), any(), any());
    }

    @Test
    @DisplayName("syncStaffRole reconciles ACTIVE staff by ensuring Keycloak role is present")
    void syncStaffRole_grantsRole_whenActive() {
        StaffProfile profile = new StaffProfile(
                UUID.randomUUID(), null, courierSub, "STF-2026-0042", StaffRole.COURIER,
                "FIELD_LOGISTICS", "ZONE-1", StaffStatus.ACTIVE, Instant.now(), Instant.now()
        );

        when(staffProfiles.findByKeycloakSub(courierSub)).thenReturn(Optional.of(profile));
        when(identityAdapter.reconcileRole(courierSub, "COURIER", true))
                .thenReturn(ReconciliationResult.success(courierSub, "COURIER", "Role granted"));

        ReconciliationResult result = service.syncStaffRole(adminIdentity, courierSub);

        assertThat(result.outcome()).isEqualTo(ReconciliationOutcome.SUCCESS);
        verify(identityAdapter).reconcileRole(courierSub, "COURIER", true);
    }

    @Test
    @DisplayName("syncStaffRole reconciles SUSPENDED staff by revoking Keycloak role")
    void syncStaffRole_revokesRole_whenSuspended() {
        StaffProfile profile = new StaffProfile(
                UUID.randomUUID(), null, courierSub, "STF-2026-0042", StaffRole.COURIER,
                "FIELD_LOGISTICS", "ZONE-1", StaffStatus.SUSPENDED, Instant.now(), Instant.now()
        );

        when(staffProfiles.findByKeycloakSub(courierSub)).thenReturn(Optional.of(profile));
        when(identityAdapter.reconcileRole(courierSub, "COURIER", false))
                .thenReturn(ReconciliationResult.success(courierSub, "COURIER", "Role revoked"));

        ReconciliationResult result = service.syncStaffRole(adminIdentity, courierSub);

        assertThat(result.outcome()).isEqualTo(ReconciliationOutcome.SUCCESS);
        verify(identityAdapter).reconcileRole(courierSub, "COURIER", false);
    }

    @Test
    @DisplayName("suspendStaff updates business status and immediately revokes Keycloak role")
    void suspendStaff_updatesStatus_andRevokesRole() {
        StaffProfile profile = new StaffProfile(
                UUID.randomUUID(), null, courierSub, "STF-2026-0042", StaffRole.COURIER,
                "FIELD_LOGISTICS", "ZONE-1", StaffStatus.ACTIVE, Instant.now(), Instant.now()
        );

        when(staffProfiles.findByKeycloakSub(courierSub)).thenReturn(Optional.of(profile));
        when(identityAdapter.reconcileRole(courierSub, "COURIER", false))
                .thenReturn(ReconciliationResult.success(courierSub, "COURIER", "Role revoked"));

        ReconciliationResult result = service.suspendStaff(adminIdentity, courierSub, "Policy violation");

        assertThat(result.outcome()).isEqualTo(ReconciliationOutcome.SUCCESS);
        assertThat(profile.getStatus()).isEqualTo(StaffStatus.SUSPENDED);
        verify(staffProfiles).save(profile);
        verify(identityAdapter).reconcileRole(courierSub, "COURIER", false);
    }

    @Test
    @DisplayName("activateStaff reconciles Keycloak before enabling business access")
    void activateStaff_reconcilesBeforePersistingActive() {
        StaffProfile profile = new StaffProfile(
                UUID.randomUUID(), null, courierSub, "STF-2026-0042", StaffRole.COURIER,
                "FIELD_LOGISTICS", "ZONE-1", StaffStatus.SUSPENDED, Instant.now(), Instant.now()
        );
        when(staffProfiles.findByKeycloakSub(courierSub)).thenReturn(Optional.of(profile));
        when(identityAdapter.identityExists(courierSub)).thenReturn(true);
        when(identityAdapter.reconcileRole(courierSub, "COURIER", true))
                .thenReturn(ReconciliationResult.success(courierSub, "COURIER", "Role granted"));

        ReconciliationResult result = service.activateStaff(adminIdentity, courierSub, "REACTIVATE");

        assertThat(result.outcome()).isEqualTo(ReconciliationOutcome.SUCCESS);
        assertThat(profile.getStatus()).isEqualTo(StaffStatus.ACTIVE);
        verify(identityAdapter).reconcileRole(courierSub, "COURIER", true);
        verify(staffProfiles).save(profile);
    }

    @Test
    @DisplayName("activation does not enable business access when Keycloak reconciliation is pending")
    void activateStaff_failsClosedWhenReconciliationPending() {
        StaffProfile profile = new StaffProfile(
                UUID.randomUUID(), null, courierSub, "STF-2026-0042", StaffRole.COURIER,
                "FIELD_LOGISTICS", "ZONE-1", StaffStatus.SUSPENDED, Instant.now(), Instant.now()
        );
        when(staffProfiles.findByKeycloakSub(courierSub)).thenReturn(Optional.of(profile));
        when(identityAdapter.identityExists(courierSub)).thenReturn(true);
        when(identityAdapter.reconcileRole(courierSub, "COURIER", true))
                .thenReturn(ReconciliationResult.pendingRetry(courierSub, "COURIER", "Keycloak unavailable"));

        assertThatThrownBy(() -> service.activateStaff(adminIdentity, courierSub, "REACTIVATE"))
                .isInstanceOf(com.swiftdrop.accountsservice.identity.keycloak.KeycloakAdminUnavailableException.class);
        assertThat(profile.getStatus()).isEqualTo(StaffStatus.SUSPENDED);
        verify(staffProfiles, never()).save(any());
    }

    @Test
    @DisplayName("invalid lifecycle transition is denied and audited")
    void lifecycleTransition_rejectsInactiveToSuspended() {
        StaffProfile profile = new StaffProfile(
                UUID.randomUUID(), null, courierSub, "STF-2026-0042", StaffRole.COURIER,
                "FIELD_LOGISTICS", "ZONE-1", StaffStatus.INACTIVE, Instant.now(), Instant.now()
        );
        when(staffProfiles.findByKeycloakSub(courierSub)).thenReturn(Optional.of(profile));

        assertThatThrownBy(() -> service.suspendStaff(adminIdentity, courierSub, "SUSPEND"))
                .isInstanceOf(IllegalStateException.class);
        verify(identityAdapter, never()).reconcileRole(any(), any(), anyBoolean());
        verify(auditService).recordAudit(eq(courierSub), eq("COURIER"), eq("STATUS_SYNC"),
                eq("INACTIVE"), eq("SUSPENDED"), eq(ReconciliationOutcome.DENIED), eq(adminSub), any(), any());
    }
}
