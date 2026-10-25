package com.swiftdrop.accountsservice.reconciliation;

import com.swiftdrop.accountsservice.identity.keycloak.ReconciliationOutcome;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * JPA entity representing a durable audit record of an identity role reconciliation attempt.
 */
@Entity
@Table(name = "identity_reconciliation_audit")
public class IdentityReconciliationAudit {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "target_sub", nullable = false, updatable = false, length = 255)
    private String targetSub;

    @Column(name = "target_role", nullable = false, updatable = false, length = 50)
    private String targetRole;

    @Column(nullable = false, updatable = false, length = 50)
    private String action;

    @Column(name = "status_before", length = 50)
    private String statusBefore;

    @Column(name = "status_after", length = 50)
    private String statusAfter;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private ReconciliationOutcome outcome;

    @Column(name = "initiated_by", nullable = false, updatable = false, length = 255)
    private String initiatedBy;

    @Column(name = "correlation_id", length = 255)
    private String correlationId;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected IdentityReconciliationAudit() {}

    public IdentityReconciliationAudit(UUID id, String targetSub, String targetRole, String action,
                                       String statusBefore, String statusAfter, ReconciliationOutcome outcome,
                                       String initiatedBy, String correlationId, int attemptCount,
                                       String errorMessage, Instant createdAt, Instant updatedAt) {
        this.id = Objects.requireNonNull(id, "ID is required");
        this.targetSub = Objects.requireNonNull(targetSub, "Target subject is required");
        this.targetRole = Objects.requireNonNull(targetRole, "Target role is required");
        this.action = Objects.requireNonNull(action, "Action is required");
        this.statusBefore = statusBefore;
        this.statusAfter = statusAfter;
        this.outcome = Objects.requireNonNull(outcome, "Outcome is required");
        this.initiatedBy = Objects.requireNonNull(initiatedBy, "Initiated by is required");
        this.correlationId = correlationId;
        this.attemptCount = attemptCount;
        this.errorMessage = errorMessage;
        this.createdAt = Objects.requireNonNull(createdAt, "Created at is required");
        this.updatedAt = Objects.requireNonNull(updatedAt, "Updated at is required");
    }

    public UUID getId() { return id; }
    public String getTargetSub() { return targetSub; }
    public String getTargetRole() { return targetRole; }
    public String getAction() { return action; }
    public String getStatusBefore() { return statusBefore; }
    public String getStatusAfter() { return statusAfter; }
    public ReconciliationOutcome getOutcome() { return outcome; }
    public String getInitiatedBy() { return initiatedBy; }
    public String getCorrelationId() { return correlationId; }
    public int getAttemptCount() { return attemptCount; }
    public String getErrorMessage() { return errorMessage; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void updateOutcome(ReconciliationOutcome outcome, int attemptCount, String statusAfter, String errorMessage, Instant updatedAt) {
        this.outcome = Objects.requireNonNull(outcome, "Outcome is required");
        this.attemptCount = attemptCount;
        this.statusAfter = statusAfter;
        this.errorMessage = errorMessage;
        this.updatedAt = Objects.requireNonNull(updatedAt, "Updated at is required");
    }
}
