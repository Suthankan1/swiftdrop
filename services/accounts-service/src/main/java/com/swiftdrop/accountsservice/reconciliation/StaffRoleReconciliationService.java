package com.swiftdrop.accountsservice.reconciliation;

import com.swiftdrop.accountsservice.identity.TrustedIdentity;
import com.swiftdrop.accountsservice.identity.keycloak.IdentityNotFoundException;
import com.swiftdrop.accountsservice.identity.keycloak.KeycloakAdminException;
import com.swiftdrop.accountsservice.identity.keycloak.KeycloakIdentityAdapter;
import com.swiftdrop.accountsservice.identity.keycloak.KeycloakRoleAllowlist;
import com.swiftdrop.accountsservice.identity.keycloak.ReconciliationOutcome;
import com.swiftdrop.accountsservice.identity.keycloak.ReconciliationResult;
import com.swiftdrop.accountsservice.identity.keycloak.UnapprovedRoleException;
import com.swiftdrop.accountsservice.staff.ProvisionStaffCommand;
import com.swiftdrop.accountsservice.staff.StaffAccessDeniedException;
import com.swiftdrop.accountsservice.staff.StaffProfile;
import com.swiftdrop.accountsservice.staff.StaffProfileNotFoundException;
import com.swiftdrop.accountsservice.staff.StaffProfileRepository;
import com.swiftdrop.accountsservice.staff.StaffProfileResponse;
import com.swiftdrop.accountsservice.staff.StaffProvisioningException;
import com.swiftdrop.accountsservice.staff.StaffRole;
import com.swiftdrop.accountsservice.staff.StaffStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Service orchestrating controlled reconciliation between Keycloak realm identities
 * and Accounts-owned staff business profiles.
 *
 * Enforces:
 * - Only verified platform ADMINs can provision or modify staff roles.
 * - Keycloak identity existence verification before profile creation.
 * - Reconciles Keycloak role assignment idempotently.
 * - Enforces business status: only ACTIVE staff retain operational roles; SUSPENDED/INACTIVE
 *   have privileged roles revoked.
 * - Fail-closed partial-failure handling with durable audit logging.
 */
@Service
@Transactional
public class StaffRoleReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(StaffRoleReconciliationService.class);

    private final StaffProfileRepository staffProfiles;
    private final KeycloakIdentityAdapter identityAdapter;
    private final ReconciliationAuditService auditService;

    public StaffRoleReconciliationService(StaffProfileRepository staffProfiles,
                                          KeycloakIdentityAdapter identityAdapter,
                                          @Autowired(required = false) ReconciliationAuditService auditService) {
        this.staffProfiles = Objects.requireNonNull(staffProfiles, "StaffProfileRepository is required");
        this.identityAdapter = Objects.requireNonNull(identityAdapter, "KeycloakIdentityAdapter is required");
        this.auditService = auditService;
    }

    /**
     * Controlled administrative provisioning of a staff profile and immediate Keycloak role reconciliation.
     */
    public StaffProfileResponse provisionStaff(TrustedIdentity adminIdentity, ProvisionStaffCommand command) {
        validateAdminCaller(adminIdentity);
        Objects.requireNonNull(command, "ProvisionStaffCommand is required");

        String targetRole = command.role().name();
        if (!KeycloakRoleAllowlist.isStaffRole(targetRole)) {
            throw new UnapprovedRoleException("Role '" + targetRole + "' is not an approved staff role.");
        }

        // Verify Keycloak identity existence
        if (!identityAdapter.identityExists(command.keycloakSub())) {
            recordAudit(command.keycloakSub(), targetRole, "PROVISION_STAFF", "NONE", "FAILED",
                    ReconciliationOutcome.FAILED, adminIdentity.subject(), "Target Keycloak identity does not exist");
            throw new IdentityNotFoundException(command.keycloakSub());
        }

        // Check for existing profile (idempotency vs conflict)
        Optional<StaffProfile> existingOpt = staffProfiles.findByKeycloakSub(command.keycloakSub());
        if (existingOpt.isPresent()) {
            StaffProfile existing = existingOpt.get();
            if (existing.getRole() == command.role() && existing.getStatus() == StaffStatus.ACTIVE
                    && existing.getStaffId().equals(command.staffId())) {
                // Idempotent retry: ensure Keycloak role is synchronized
                Set<String> roles = identityAdapter.getUserRoles(command.keycloakSub());
                if (!roles.contains(targetRole)) {
                    identityAdapter.grantRole(command.keycloakSub(), targetRole);
                }
                recordAudit(command.keycloakSub(), targetRole, "PROVISION_STAFF", "ACTIVE", "ACTIVE",
                        ReconciliationOutcome.NO_OP, adminIdentity.subject(), null);
                return mapToResponse(existing);
            }
            recordAudit(command.keycloakSub(), targetRole, "PROVISION_STAFF", existing.getStatus().name(), existing.getStatus().name(),
                    ReconciliationOutcome.DENIED, adminIdentity.subject(), "Staff profile already exists with conflicting attributes");
            throw new StaffProvisioningException("Staff profile already exists for subject: " + command.keycloakSub());
        }

        if (staffProfiles.existsByStaffId(command.staffId())) {
            recordAudit(command.keycloakSub(), targetRole, "PROVISION_STAFF", "NONE", "DENIED",
                    ReconciliationOutcome.DENIED, adminIdentity.subject(), "Staff ID is already assigned: " + command.staffId());
            throw new StaffProvisioningException("Staff ID is already assigned: " + command.staffId());
        }

        // Persist business profile
        UUID profileId = UUID.randomUUID();
        Instant now = Instant.now();
        StaffProfile profile = new StaffProfile(
                profileId,
                command.accountProfileId(),
                command.keycloakSub(),
                command.staffId(),
                command.role(),
                command.department(),
                command.assignedZone(),
                StaffStatus.ACTIVE,
                now,
                now
        );
        StaffProfile saved = staffProfiles.save(profile);

        // Reconcile role in Keycloak
        try {
            identityAdapter.grantRole(command.keycloakSub(), targetRole);
            recordAudit(command.keycloakSub(), targetRole, "PROVISION_STAFF", "NONE", "ACTIVE",
                    ReconciliationOutcome.SUCCESS, adminIdentity.subject(), null);
        } catch (KeycloakAdminException e) {
            log.error("Keycloak role grant failed during staff provisioning for subject {}", command.keycloakSub(), e);
            recordAudit(command.keycloakSub(), targetRole, "PROVISION_STAFF", "NONE", "PENDING_RETRY",
                    ReconciliationOutcome.PENDING_RETRY, adminIdentity.subject(), e.getMessage());
            // Fail closed: throw exception so caller knows reconciliation is unresolved
            throw e;
        }

        return mapToResponse(saved);
    }

    /**
     * Synchronizes a staff member's Keycloak roles to match their authoritative business status.
     */
    public ReconciliationResult syncStaffRole(TrustedIdentity adminIdentity, String targetSub) {
        validateAdminCaller(adminIdentity);
        if (targetSub == null || targetSub.isBlank()) {
            throw new IllegalArgumentException("Target subject is required");
        }

        StaffProfile profile = staffProfiles.findByKeycloakSub(targetSub)
                .orElseThrow(() -> new StaffProfileNotFoundException("Staff profile not found for subject: " + targetSub));

        String targetRole = profile.getRole().name();
        boolean shouldHaveRole = profile.getStatus().isActive();

        ReconciliationResult result = identityAdapter.reconcileRole(targetSub, targetRole, shouldHaveRole);

        recordAudit(targetSub, targetRole, "STATUS_SYNC", profile.getStatus().name(),
                shouldHaveRole ? "ACTIVE" : profile.getStatus().name(),
                result.outcome(), adminIdentity.subject(), result.message());

        return result;
    }

    /**
     * Suspends a staff member and revokes their privileged operational role in Keycloak.
     */
    public ReconciliationResult suspendStaff(TrustedIdentity adminIdentity, String targetSub, String reason) {
        validateAdminCaller(adminIdentity);
        if (targetSub == null || targetSub.isBlank()) {
            throw new IllegalArgumentException("Target subject is required");
        }

        StaffProfile profile = staffProfiles.findByKeycloakSub(targetSub)
                .orElseThrow(() -> new StaffProfileNotFoundException("Staff profile not found for subject: " + targetSub));

        StaffStatus statusBefore = profile.getStatus();
        profile.setStatus(StaffStatus.SUSPENDED, Instant.now());
        staffProfiles.save(profile);

        String targetRole = profile.getRole().name();
        ReconciliationResult result = identityAdapter.reconcileRole(targetSub, targetRole, false);

        recordAudit(targetSub, targetRole, "REVOKE_ROLE", statusBefore.name(), StaffStatus.SUSPENDED.name(),
                result.outcome(), adminIdentity.subject(), "Suspended: " + (reason != null ? reason : ""));

        return result;
    }

    private void validateAdminCaller(TrustedIdentity adminIdentity) {
        if (adminIdentity == null || adminIdentity.subject() == null || adminIdentity.subject().isBlank()) {
            throw new StaffAccessDeniedException("Authenticated administrator identity is required");
        }
        if (!adminIdentity.roles().contains("ADMIN")) {
            throw new StaffAccessDeniedException("Operation requires authenticated ADMIN role. Caller roles: " + adminIdentity.roles());
        }
    }

    private void recordAudit(String targetSub, String targetRole, String action, String statusBefore,
                             String statusAfter, ReconciliationOutcome outcome, String initiatedBy, String errorMessage) {
        if (auditService != null) {
            try {
                auditService.recordAudit(targetSub, targetRole, action, statusBefore, statusAfter,
                        outcome, initiatedBy, null, errorMessage);
            } catch (Exception e) {
                log.warn("Failed to persist reconciliation audit record for subject {}", targetSub, e);
            }
        }
    }

    private StaffProfileResponse mapToResponse(StaffProfile profile) {
        return new StaffProfileResponse(
                profile.getId(),
                profile.getStaffId(),
                profile.getKeycloakSub(),
                "",
                "",
                "",
                profile.getRole(),
                profile.getDepartment(),
                profile.getAssignedZone(),
                profile.getStatus(),
                profile.getCreatedAt(),
                profile.getUpdatedAt()
        );
    }
}
