package com.swiftdrop.accountsservice.merchant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable audit record of merchant administrative business decisions.
 * Persisted durably in accounts_db to guarantee accountability for merchant
 * approval and suspension commands.
 */
@Entity
@Table(name = "merchant_business_audit")
public class MerchantBusinessAudit {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "merchant_id", nullable = false, updatable = false)
    private UUID merchantId;

    @Column(name = "actor_sub", nullable = false, updatable = false, length = 255)
    private String actorSub;

    @Column(nullable = false, updatable = false, length = 50)
    private String action;

    @Column(name = "previous_status", nullable = false, updatable = false, length = 30)
    private String previousStatus;

    @Column(name = "new_status", nullable = false, updatable = false, length = 30)
    private String newStatus;

    @Column(updatable = false)
    private String reason;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "correlation_id", updatable = false, length = 255)
    private String correlationId;

    @Column(name = "merchant_version", nullable = false, updatable = false)
    private Long merchantVersion;

    @Column(nullable = false, updatable = false, length = 50)
    private String outcome;

    @Column(name = "reconciliation_outcome", length = 50)
    private String reconciliationOutcome;

    @Column(name = "reconciliation_message")
    private String reconciliationMessage;

    protected MerchantBusinessAudit() { }

    public MerchantBusinessAudit(UUID id, UUID merchantId, String actorSub, String action,
                                 String previousStatus, String newStatus, String reason,
                                 Instant occurredAt, String correlationId, Long merchantVersion,
                                 String outcome, String reconciliationOutcome, String reconciliationMessage) {
        this.id = Objects.requireNonNull(id, "Audit ID is required");
        this.merchantId = Objects.requireNonNull(merchantId, "Merchant ID is required");
        this.actorSub = Objects.requireNonNull(actorSub, "Actor subject is required");
        this.action = Objects.requireNonNull(action, "Action is required");
        this.previousStatus = Objects.requireNonNull(previousStatus, "Previous status is required");
        this.newStatus = Objects.requireNonNull(newStatus, "New status is required");
        this.reason = reason;
        this.occurredAt = Objects.requireNonNull(occurredAt, "Occurred at timestamp is required");
        this.correlationId = correlationId;
        this.merchantVersion = Objects.requireNonNull(merchantVersion, "Merchant version is required");
        this.outcome = Objects.requireNonNull(outcome, "Outcome is required");
        this.reconciliationOutcome = reconciliationOutcome;
        this.reconciliationMessage = reconciliationMessage;
    }

    public UUID getId() { return id; }
    public UUID getMerchantId() { return merchantId; }
    public String getActorSub() { return actorSub; }
    public String getAction() { return action; }
    public String getPreviousStatus() { return previousStatus; }
    public String getNewStatus() { return newStatus; }
    public String getReason() { return reason; }
    public Instant getOccurredAt() { return occurredAt; }
    public String getCorrelationId() { return correlationId; }
    public Long getMerchantVersion() { return merchantVersion; }
    public String getOutcome() { return outcome; }
    public String getReconciliationOutcome() { return reconciliationOutcome; }
    public String getReconciliationMessage() { return reconciliationMessage; }

    public void setReconciliationOutcome(String reconciliationOutcome, String reconciliationMessage) {
        this.reconciliationOutcome = reconciliationOutcome;
        this.reconciliationMessage = reconciliationMessage;
    }
}
