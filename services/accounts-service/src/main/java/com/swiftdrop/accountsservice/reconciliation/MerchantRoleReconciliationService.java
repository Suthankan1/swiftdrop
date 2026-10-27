package com.swiftdrop.accountsservice.reconciliation;

import com.swiftdrop.accountsservice.identity.TrustedIdentity;
import com.swiftdrop.accountsservice.identity.keycloak.KeycloakAdminException;
import com.swiftdrop.accountsservice.identity.keycloak.KeycloakIdentityAdapter;
import com.swiftdrop.accountsservice.identity.keycloak.ReconciliationOutcome;
import com.swiftdrop.accountsservice.identity.keycloak.ReconciliationResult;
import com.swiftdrop.accountsservice.merchant.Merchant;
import com.swiftdrop.accountsservice.merchant.MerchantBusinessAudit;
import com.swiftdrop.accountsservice.merchant.MerchantBusinessAuditRepository;
import com.swiftdrop.accountsservice.merchant.MerchantConcurrencyConflictException;
import com.swiftdrop.accountsservice.merchant.MerchantDecisionValidationException;
import com.swiftdrop.accountsservice.merchant.MerchantIllegalStateTransitionException;
import com.swiftdrop.accountsservice.merchant.MerchantNotFoundException;
import com.swiftdrop.accountsservice.merchant.MerchantRepository;
import com.swiftdrop.accountsservice.merchant.MerchantStatus;
import com.swiftdrop.accountsservice.staff.StaffAccessDeniedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Service orchestrating controlled reconciliation between Keycloak realm identities
 * and Accounts-owned merchant tenant records.
 *
 * Implements:
 * - Gated trigger policy: ON_APPROVAL (default standard) vs ON_ENROLLMENT.
 * - Stale retry protection: Authoritative Accounts business status is always re-read before mutation.
 * - Idempotent grant, revocation, and status synchronization.
 * - Prevents public self-assignment of MERCHANT role.
 */
@Service
@Transactional
public class MerchantRoleReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(MerchantRoleReconciliationService.class);

    private final MerchantRepository merchantRepository;
    private final KeycloakIdentityAdapter identityAdapter;
    private final ReconciliationAuditService auditService;
    private final MerchantBusinessAuditRepository businessAuditRepository;
    private final MerchantReconciliationTriggerPolicy triggerPolicy;

    public MerchantRoleReconciliationService(
            MerchantRepository merchantRepository,
            KeycloakIdentityAdapter identityAdapter,
            @Autowired(required = false) ReconciliationAuditService auditService,
            @Value("${swiftdrop.reconciliation.merchant-role-trigger:ON_APPROVAL}") String triggerPolicyStr) {
        this(merchantRepository, identityAdapter, auditService, null, triggerPolicyStr);
    }

    @Autowired
    public MerchantRoleReconciliationService(
            MerchantRepository merchantRepository,
            KeycloakIdentityAdapter identityAdapter,
            @Autowired(required = false) ReconciliationAuditService auditService,
            @Autowired(required = false) MerchantBusinessAuditRepository businessAuditRepository,
            @Value("${swiftdrop.reconciliation.merchant-role-trigger:ON_APPROVAL}") String triggerPolicyStr) {
        this.merchantRepository = Objects.requireNonNull(merchantRepository, "MerchantRepository is required");
        this.identityAdapter = Objects.requireNonNull(identityAdapter, "KeycloakIdentityAdapter is required");
        this.auditService = auditService;
        this.businessAuditRepository = businessAuditRepository;
        this.triggerPolicy = parsePolicy(triggerPolicyStr);
    }

    public MerchantReconciliationTriggerPolicy getTriggerPolicy() {
        return triggerPolicy;
    }

    /**
     * Reconciles the Keycloak MERCHANT role for a verified subject based on their current authoritative
     * business status in Accounts and the configured trigger policy.
     */
    public ReconciliationResult reconcileMerchantRole(TrustedIdentity callerIdentity, String targetSub) {
        if (targetSub == null || targetSub.isBlank()) {
            throw new IllegalArgumentException("Target subject is required");
        }

        Merchant merchant = merchantRepository.findByKeycloakSub(targetSub)
                .orElseThrow(() -> new MerchantNotFoundException("Merchant record not found for subject: " + targetSub));

        boolean shouldHaveRole = triggerPolicy.shouldHaveMerchantRole(merchant.getStatus());

        ReconciliationResult result = identityAdapter.reconcileRole(targetSub, "MERCHANT", shouldHaveRole);

        String initiator = callerIdentity != null && callerIdentity.subject() != null ? callerIdentity.subject() : "SYSTEM";
        recordAudit(targetSub, "MERCHANT", "STATUS_SYNC", merchant.getStatus().name(),
                shouldHaveRole ? "MERCHANT_GRANTED" : "MERCHANT_REVOKED",
                result.outcome(), initiator, result.message());

        return result;
    }

    /**
     * Administrative approval of a merchant tenant and role synchronization.
     */
    public ReconciliationResult approveMerchant(TrustedIdentity adminIdentity, UUID merchantId) {
        return approveMerchant(adminIdentity, merchantId, null, null);
    }

    /**
     * Administrative approval with optimistic concurrency check and correlation ID.
     */
    @Transactional(noRollbackFor = KeycloakAdminException.class)
    public ReconciliationResult approveMerchant(TrustedIdentity adminIdentity, UUID merchantId,
                                                Long expectedVersion, String correlationId) {
        validateAdminCaller(adminIdentity);
        Objects.requireNonNull(merchantId, "Merchant ID is required");

        Merchant merchant = merchantRepository.findById(merchantId)
                .orElseThrow(() -> new MerchantNotFoundException("Merchant not found: " + merchantId));

        MerchantStatus statusBefore = merchant.getStatus();
        if (statusBefore == MerchantStatus.APPROVED) {
            throw new MerchantIllegalStateTransitionException("Merchant is already APPROVED; repeat approval rejected.");
        }
        if (statusBefore != MerchantStatus.PENDING) {
            throw new MerchantIllegalStateTransitionException(
                    "Approval requires PENDING status. Current status: " + statusBefore
            );
        }

        if (expectedVersion != null && !Objects.equals(merchant.getVersion(), expectedVersion)) {
            throw new MerchantConcurrencyConflictException(
                    "Merchant record has changed concurrently (expected version " + expectedVersion +
                    ", current version " + merchant.getVersion() + "); review refreshed state before retry."
            );
        }

        merchant.setStatus(MerchantStatus.APPROVED, Instant.now());
        merchantRepository.save(merchant);

        MerchantBusinessAudit businessAudit = null;
        if (businessAuditRepository != null) {
            businessAudit = new MerchantBusinessAudit(
                    UUID.randomUUID(),
                    merchant.getId(),
                    adminIdentity.subject(),
                    "APPROVE",
                    statusBefore.name(),
                    MerchantStatus.APPROVED.name(),
                    "ADMIN_APPROVED",
                    Instant.now(),
                    correlationId,
                    merchant.getVersion() != null ? merchant.getVersion() : 0L,
                    "SUCCESS",
                    null,
                    null
            );
            businessAuditRepository.save(businessAudit);
        }

        // Under ON_APPROVAL policy, APPROVED status triggers role grant
        ReconciliationResult result;
        try {
            result = identityAdapter.reconcileRole(merchant.getKeycloakSub(), "MERCHANT", true);
            if (businessAudit != null) {
                businessAudit.setReconciliationOutcome(result.outcome().name(), result.message());
                businessAuditRepository.save(businessAudit);
            }
        } catch (KeycloakAdminException ex) {
            log.warn("Keycloak role grant failed for merchant {} ({}); Accounts DB state remains APPROVED with reconciliation PENDING_RETRY: {}",
                    merchant.getId(), merchant.getKeycloakSub(), ex.getMessage());
            if (businessAudit != null) {
                businessAudit.setReconciliationOutcome("PENDING_RETRY", ex.getMessage());
                businessAuditRepository.save(businessAudit);
            }
            recordAudit(merchant.getKeycloakSub(), "MERCHANT", "APPROVE_MERCHANT", statusBefore.name(),
                    MerchantStatus.APPROVED.name(), ReconciliationOutcome.PENDING_RETRY, adminIdentity.subject(), ex.getMessage());
            return ReconciliationResult.failed(merchant.getKeycloakSub(), "MERCHANT", "Keycloak role grant failed; scheduled for retry: " + ex.getMessage());
        }

        recordAudit(merchant.getKeycloakSub(), "MERCHANT", "APPROVE_MERCHANT", statusBefore.name(),
                MerchantStatus.APPROVED.name(), result.outcome(), adminIdentity.subject(), result.message());

        return result;
    }

    /**
     * Administrative suspension of a merchant tenant and revocation of the MERCHANT role.
     */
    public ReconciliationResult suspendMerchant(TrustedIdentity adminIdentity, UUID merchantId, String reason) {
        return suspendMerchant(adminIdentity, merchantId, reason, null, null);
    }

    /**
     * Administrative suspension with reason validation, optimistic concurrency check, and durable audit.
     */
    @Transactional(noRollbackFor = KeycloakAdminException.class)
    public ReconciliationResult suspendMerchant(TrustedIdentity adminIdentity, UUID merchantId, String reason,
                                                Long expectedVersion, String correlationId) {
        validateAdminCaller(adminIdentity);
        Objects.requireNonNull(merchantId, "Merchant ID is required");

        if (reason == null || reason.trim().isEmpty()) {
            throw new MerchantDecisionValidationException("Suspension requires a non-empty reason.");
        }
        String sanitizedReason = reason.trim();
        if (sanitizedReason.length() > 500) {
            throw new MerchantDecisionValidationException("Suspension reason cannot exceed 500 characters.");
        }

        Merchant merchant = merchantRepository.findById(merchantId)
                .orElseThrow(() -> new MerchantNotFoundException("Merchant not found: " + merchantId));

        MerchantStatus statusBefore = merchant.getStatus();
        if (statusBefore == MerchantStatus.SUSPENDED) {
            throw new MerchantIllegalStateTransitionException("Merchant is already SUSPENDED; repeat suspension rejected.");
        }
        if (statusBefore != MerchantStatus.APPROVED) {
            throw new MerchantIllegalStateTransitionException(
                    "Suspension is only permitted for APPROVED merchants. Current status: " + statusBefore
            );
        }

        if (expectedVersion != null && !Objects.equals(merchant.getVersion(), expectedVersion)) {
            throw new MerchantConcurrencyConflictException(
                    "Merchant record has changed concurrently (expected version " + expectedVersion +
                    ", current version " + merchant.getVersion() + "); review refreshed state before retry."
            );
        }

        // Immediately revoke business status in Accounts
        merchant.setStatus(MerchantStatus.SUSPENDED, Instant.now());
        merchantRepository.save(merchant);

        MerchantBusinessAudit businessAudit = null;
        if (businessAuditRepository != null) {
            businessAudit = new MerchantBusinessAudit(
                    UUID.randomUUID(),
                    merchant.getId(),
                    adminIdentity.subject(),
                    "SUSPEND",
                    statusBefore.name(),
                    MerchantStatus.SUSPENDED.name(),
                    sanitizedReason,
                    Instant.now(),
                    correlationId,
                    merchant.getVersion() != null ? merchant.getVersion() : 0L,
                    "SUCCESS",
                    null,
                    null
            );
            businessAuditRepository.save(businessAudit);
        }

        // Immediate revocation of the MERCHANT role in Keycloak
        ReconciliationResult result;
        try {
            result = identityAdapter.reconcileRole(merchant.getKeycloakSub(), "MERCHANT", false);
            if (businessAudit != null) {
                businessAudit.setReconciliationOutcome(result.outcome().name(), result.message());
                businessAuditRepository.save(businessAudit);
            }
        } catch (KeycloakAdminException ex) {
            log.warn("Keycloak role revocation failed for merchant {} ({}); Accounts DB state remains SUSPENDED with reconciliation PENDING_RETRY: {}",
                    merchant.getId(), merchant.getKeycloakSub(), ex.getMessage());
            if (businessAudit != null) {
                businessAudit.setReconciliationOutcome("PENDING_RETRY", ex.getMessage());
                businessAuditRepository.save(businessAudit);
            }
            recordAudit(merchant.getKeycloakSub(), "MERCHANT", "SUSPEND_MERCHANT", statusBefore.name(),
                    MerchantStatus.SUSPENDED.name(), ReconciliationOutcome.PENDING_RETRY, adminIdentity.subject(),
                    "Suspended: " + sanitizedReason + " [Error: " + ex.getMessage() + "]");
            return ReconciliationResult.failed(merchant.getKeycloakSub(), "MERCHANT", "Keycloak role revocation failed; scheduled for retry: " + ex.getMessage());
        }

        recordAudit(merchant.getKeycloakSub(), "MERCHANT", "SUSPEND_MERCHANT", statusBefore.name(),
                MerchantStatus.SUSPENDED.name(), result.outcome(), adminIdentity.subject(),
                "Suspended: " + sanitizedReason);

        return result;
    }

    /**
     * Hook invoked during merchant enrollment. Under ON_APPROVAL policy, this is a safe no-op.
     */
    public ReconciliationResult handleEnrollment(String targetSub) {
        if (targetSub == null || targetSub.isBlank()) {
            throw new IllegalArgumentException("Target subject is required");
        }

        if (triggerPolicy == MerchantReconciliationTriggerPolicy.ON_APPROVAL) {
            log.debug("Merchant enrollment for subject {} does not grant MERCHANT role under ON_APPROVAL policy", targetSub);
            return ReconciliationResult.noOp(targetSub, "MERCHANT",
                    "Role grant deferred until administrative approval under ON_APPROVAL policy.");
        }

        // Under ON_ENROLLMENT policy, provisionally synchronize the role
        return identityAdapter.reconcileRole(targetSub, "MERCHANT", true);
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
                log.warn("Failed to persist merchant reconciliation audit record for subject {}", targetSub, e);
            }
        }
    }

    private static MerchantReconciliationTriggerPolicy parsePolicy(String policyStr) {
        if (policyStr != null && policyStr.trim().equalsIgnoreCase("ON_ENROLLMENT")) {
            return MerchantReconciliationTriggerPolicy.ON_ENROLLMENT;
        }
        return MerchantReconciliationTriggerPolicy.ON_APPROVAL;
    }
}
