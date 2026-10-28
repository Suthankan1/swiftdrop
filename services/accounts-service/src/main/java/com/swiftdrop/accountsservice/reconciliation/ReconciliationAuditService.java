package com.swiftdrop.accountsservice.reconciliation;

import com.swiftdrop.accountsservice.identity.keycloak.ReconciliationOutcome;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Service interface for recording, querying, and updating durable identity reconciliation audit events.
 */
public interface ReconciliationAuditService {

    /**
     * Records a new reconciliation audit entry.
     *
     * @param targetSub      Keycloak subject UUID being reconciled.
     * @param targetRole     role being granted or revoked.
     * @param action         reconciliation action (e.g. PROVISION_STAFF, GRANT_ROLE, REVOKE_ROLE, STATUS_SYNC).
     * @param statusBefore   business status or role state prior to operation.
     * @param statusAfter    business status or role state after operation.
     * @param outcome        reconciliation outcome (SUCCESS, NO_OP, FAILED, PENDING_RETRY, DENIED).
     * @param initiatedBy    authenticated subject of the actor initiating the change, or SYSTEM.
     * @param correlationId  correlation/tracing ID.
     * @param errorMessage   sanitized error details, if any.
     * @return unique UUID of the audit record.
     */
    UUID recordAudit(String targetSub,
                     String targetRole,
                     String action,
                     String statusBefore,
                     String statusAfter,
                     ReconciliationOutcome outcome,
                     String initiatedBy,
                     String correlationId,
                     String errorMessage);

    /**
     * Updates an existing audit record with retry outcome evidence.
     *
     * @param auditId      UUID of the audit record.
     * @param outcome      new outcome state.
     * @param attemptCount total attempt count.
     * @param statusAfter  new verified state.
     * @param errorMessage sanitized error details, if any.
     */
    void updateAuditOutcome(UUID auditId,
                            ReconciliationOutcome outcome,
                            int attemptCount,
                            String statusAfter,
                            String errorMessage);

    /**
     * Finds an audit record by its unique identifier.
     */
    Optional<IdentityReconciliationAudit> findById(UUID id);

    /**
     * Finds all audit records for a specific Keycloak subject ordered newest first.
     */
    List<IdentityReconciliationAudit> findByTargetSub(String targetSub);
}
