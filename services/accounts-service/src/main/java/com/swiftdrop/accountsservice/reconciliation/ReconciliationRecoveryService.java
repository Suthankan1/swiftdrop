package com.swiftdrop.accountsservice.reconciliation;

import com.swiftdrop.accountsservice.identity.TrustedIdentity;
import com.swiftdrop.accountsservice.identity.keycloak.IdentityNotFoundException;
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
import com.swiftdrop.accountsservice.staff.StaffStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Service responsible for partial-failure recovery, idempotent retry, and fail-closed
 * reconciliation resolution between Accounts domain state and Keycloak roles.
 *
 * <p>CRITICAL SECURITY INVARIANT:
 * Retries never blindly re-execute the original operation. Instead, they re-read the
 * authoritative current Accounts state. If a target entity was suspended after an earlier
 * failure, the retry strictly enforces revocation and never restores privileges.
 */
@Service
public class ReconciliationRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationRecoveryService.class);

    private final ReconciliationAuditService auditService;
    private final StaffRoleReconciliationService staffReconciliationService;
    private final MerchantRoleReconciliationService merchantReconciliationService;
    private final StaffProfileRepository staffRepository;
    private final MerchantRepository merchantRepository;
    private final KeycloakIdentityAdapter keycloakAdapter;

    public ReconciliationRecoveryService(ReconciliationAuditService auditService,
                                         StaffRoleReconciliationService staffReconciliationService,
                                         MerchantRoleReconciliationService merchantReconciliationService,
                                         StaffProfileRepository staffRepository,
                                         MerchantRepository merchantRepository,
                                         KeycloakIdentityAdapter keycloakAdapter) {
        this.auditService = Objects.requireNonNull(auditService, "auditService is required");
        this.staffReconciliationService = Objects.requireNonNull(staffReconciliationService, "staffReconciliationService is required");
        this.merchantReconciliationService = Objects.requireNonNull(merchantReconciliationService, "merchantReconciliationService is required");
        this.staffRepository = Objects.requireNonNull(staffRepository, "staffRepository is required");
        this.merchantRepository = Objects.requireNonNull(merchantRepository, "merchantRepository is required");
        this.keycloakAdapter = Objects.requireNonNull(keycloakAdapter, "keycloakAdapter is required");
    }

    /**
     * Retries a prior failed or pending reconciliation by re-reading the authoritative source state.
     *
     * @param auditId       UUID of the prior audit record.
     * @param adminIdentity the authenticated administrator executing the recovery.
     * @return the newly evaluated ReconciliationResult.
     */
    @Transactional
    public ReconciliationResult retryReconciliation(UUID auditId, TrustedIdentity adminIdentity) {
        Objects.requireNonNull(auditId, "auditId is required");
        Objects.requireNonNull(adminIdentity, "adminIdentity is required");

        if (adminIdentity.roles() == null || !adminIdentity.roles().contains(KeycloakRoleAllowlist.ADMIN)) {
            throw new AccessDeniedException("Only ADMIN actors may retry identity reconciliation");
        }

        IdentityReconciliationAudit audit = auditService.findById(auditId)
                .orElseThrow(() -> new IllegalArgumentException("Reconciliation audit record not found: " + auditId));

        String targetSub = audit.getTargetSub();
        String targetRole = audit.getTargetRole();
        int nextAttempt = audit.getAttemptCount() + 1;

        log.info("Initiating reconciliation recovery [auditId={}, targetSub={}, targetRole={}, attempt={}]",
                auditId, targetSub, targetRole, nextAttempt);

        try {
            // Re-read authoritative Accounts state
            Optional<StaffProfile> staffOpt = staffRepository.findByKeycloakSub(targetSub);
            if (staffOpt.isPresent()) {
                StaffProfile staff = staffOpt.get();
                return recoverStaffReconciliation(auditId, staff, adminIdentity, nextAttempt);
            }

            Optional<Merchant> merchantOpt = merchantRepository.findByKeycloakSub(targetSub);
            if (merchantOpt.isPresent()) {
                Merchant merchant = merchantOpt.get();
                return recoverMerchantReconciliation(auditId, merchant, adminIdentity, nextAttempt);
            }

            // Target subject has no business record in Accounts.
            // Fail closed: revoke any stale privileged role in Keycloak
            log.warn("Target subject {} has no Accounts record during recovery. Revoking role {} if present", targetSub, targetRole);
            keycloakAdapter.revokeRole(targetSub, targetRole);
            ReconciliationResult result = ReconciliationResult.denied(
                    targetSub,
                    targetRole,
                    "No Accounts business record found for subject; fail-closed enforcement applied"
            );
            auditService.updateAuditOutcome(auditId, ReconciliationOutcome.DENIED, nextAttempt, "REVOKED_ORPHAN", result.message());
            return result;

        } catch (IdentityNotFoundException e) {
            log.warn("Keycloak identity no longer exists for subject {}", targetSub);
            auditService.updateAuditOutcome(auditId, ReconciliationOutcome.FAILED, nextAttempt, "IDENTITY_NOT_FOUND", e.getMessage());
            return ReconciliationResult.failed(targetSub, targetRole, e.getMessage());
        } catch (KeycloakAdminUnavailableException e) {
            log.error("Keycloak admin unavailable during recovery retry for subject {}", targetSub, e);
            auditService.updateAuditOutcome(auditId, ReconciliationOutcome.PENDING_RETRY, nextAttempt, "UNAVAILABLE", e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("Unexpected error during reconciliation recovery for subject {}", targetSub, e);
            auditService.updateAuditOutcome(auditId, ReconciliationOutcome.FAILED, nextAttempt, "ERROR", e.getMessage());
            return ReconciliationResult.failed(targetSub, targetRole, e.getMessage());
        }
    }

    private ReconciliationResult recoverStaffReconciliation(UUID auditId, StaffProfile staff, TrustedIdentity adminIdentity, int attemptCount) {
        String targetSub = staff.getKeycloakSub();
        String staffRole = staff.getRole().name();

        if (staff.getStatus() == StaffStatus.SUSPENDED || staff.getStatus() == StaffStatus.INACTIVE) {
            log.warn("Staff {} is currently {}. Enforcing revocation during recovery (stale-grant prevention)",
                    targetSub, staff.getStatus());
            keycloakAdapter.revokeRole(targetSub, staffRole);
            auditService.updateAuditOutcome(auditId, ReconciliationOutcome.SUCCESS, attemptCount, staff.getStatus().name(),
                    "Stale-retry prevented: staff is " + staff.getStatus() + ", privileged role revoked");
            return ReconciliationResult.success(
                    targetSub,
                    staffRole,
                    "Staff is " + staff.getStatus() + "; role revoked in Keycloak"
            );
        }

        // Active staff member: synchronize role
        ReconciliationResult syncResult = staffReconciliationService.syncStaffRole(adminIdentity, targetSub);
        auditService.updateAuditOutcome(auditId, syncResult.outcome(), attemptCount, staff.getStatus().name(), syncResult.message());
        return syncResult;
    }

    private ReconciliationResult recoverMerchantReconciliation(UUID auditId, Merchant merchant, TrustedIdentity adminIdentity, int attemptCount) {
        String targetSub = merchant.getKeycloakSub();

        if (merchant.getStatus() == MerchantStatus.SUSPENDED || merchant.getStatus() == MerchantStatus.REJECTED) {
            log.warn("Merchant {} is currently {}. Enforcing revocation during recovery (stale-grant prevention)",
                    targetSub, merchant.getStatus());
            keycloakAdapter.revokeRole(targetSub, KeycloakRoleAllowlist.MERCHANT);
            auditService.updateAuditOutcome(auditId, ReconciliationOutcome.SUCCESS, attemptCount, merchant.getStatus().name(),
                    "Stale-retry prevented: merchant is " + merchant.getStatus() + ", MERCHANT role revoked");
            return ReconciliationResult.success(
                    targetSub,
                    KeycloakRoleAllowlist.MERCHANT,
                    "Merchant is " + merchant.getStatus() + "; MERCHANT role revoked in Keycloak"
            );
        }

        // Active or Pending merchant: reconcile via MerchantRoleReconciliationService
        ReconciliationResult result = merchantReconciliationService.reconcileMerchantRole(
                adminIdentity,
                targetSub
        );
        auditService.updateAuditOutcome(auditId, result.outcome(), attemptCount, merchant.getStatus().name(), result.message());
        return result;
    }
}
