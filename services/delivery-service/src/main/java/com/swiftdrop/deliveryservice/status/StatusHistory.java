package com.swiftdrop.deliveryservice.status;

import com.swiftdrop.deliveryservice.core.ParcelStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Append-only immutable status audit history record.
 *
 * Persisted atomically with parcel status mutations in delivery_db.status_history.
 */
@Entity
@Table(name = "status_history")
public class StatusHistory {

    @Id
    @Column(name = "history_id", nullable = false, updatable = false)
    private UUID historyId;

    @Column(name = "parcel_id", nullable = false, updatable = false)
    private UUID parcelId;

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_status", length = 20, updatable = false)
    private ParcelStatus previousStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "new_status", nullable = false, length = 20, updatable = false)
    private ParcelStatus newStatus;

    @Column(name = "reason", length = 64, updatable = false)
    private String reason;

    @Column(name = "note", updatable = false)
    private String note;

    @Column(name = "proof_id", updatable = false)
    private UUID proofId;

    @Column(name = "actor_sub", nullable = false, length = 64, updatable = false)
    private String actorSub;

    @Column(name = "resulting_version", nullable = false, updatable = false)
    private Long resultingVersion;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    public StatusHistory() {
    }

    public StatusHistory(UUID historyId,
                         UUID parcelId,
                         ParcelStatus previousStatus,
                         ParcelStatus newStatus,
                         String reason,
                         String note,
                         UUID proofId,
                         String actorSub,
                         Long resultingVersion,
                         Instant occurredAt) {
        this.historyId = Objects.requireNonNull(historyId, "historyId cannot be null");
        this.parcelId = Objects.requireNonNull(parcelId, "parcelId cannot be null");
        this.newStatus = Objects.requireNonNull(newStatus, "newStatus cannot be null");
        this.previousStatus = previousStatus;
        this.reason = reason;
        this.note = note;
        this.proofId = proofId;
        this.actorSub = Objects.requireNonNull(actorSub, "actorSub cannot be null");
        this.resultingVersion = Objects.requireNonNull(resultingVersion, "resultingVersion cannot be null");
        this.occurredAt = occurredAt != null ? occurredAt : Instant.now();
    }

    @PrePersist
    protected void onCreate() {
        if (this.occurredAt == null) {
            this.occurredAt = Instant.now();
        }
    }

    // Getters

    public UUID getHistoryId() {
        return historyId;
    }

    public UUID getParcelId() {
        return parcelId;
    }

    public ParcelStatus getPreviousStatus() {
        return previousStatus;
    }

    public ParcelStatus getNewStatus() {
        return newStatus;
    }

    public String getReason() {
        return reason;
    }

    public String getNote() {
        return note;
    }

    public UUID getProofId() {
        return proofId;
    }

    public String getActorSub() {
        return actorSub;
    }

    public Long getResultingVersion() {
        return resultingVersion;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        StatusHistory that = (StatusHistory) o;
        return Objects.equals(historyId, that.historyId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(historyId);
    }
}
