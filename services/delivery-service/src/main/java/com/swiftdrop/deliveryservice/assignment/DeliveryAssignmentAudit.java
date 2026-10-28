package com.swiftdrop.deliveryservice.assignment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Delivery Assignment Audit Entity mapping to delivery_db.delivery_assignment_audits.
 *
 * Implements DEC-ASG-07 for append-only audit tracking of assignment actions
 * (ASSIGNED, REASSIGNED, CLOSED, TERMINATED) strictly separate from parcel status history.
 */
@Entity
@Table(name = "delivery_assignment_audits")
public class DeliveryAssignmentAudit {

    @Id
    @Column(name = "audit_id", nullable = false, updatable = false)
    private UUID auditId;

    @Column(name = "parcel_id", nullable = false, updatable = false)
    private UUID parcelId;

    @Column(name = "assignment_id", nullable = false, updatable = false)
    private UUID assignmentId;

    @Column(name = "courier_sub", nullable = false, length = 64, updatable = false)
    private String courierSub;

    @Column(name = "action", nullable = false, length = 32, updatable = false)
    private String action;

    @Column(name = "actor_sub", nullable = false, length = 64, updatable = false)
    private String actorSub;

    @Column(name = "actor_role", nullable = false, length = 32, updatable = false)
    private String actorRole;

    @Column(name = "previous_courier_sub", length = 64, updatable = false)
    private String previousCourierSub;

    @Column(name = "previous_assignment_id", updatable = false)
    private UUID previousAssignmentId;

    @Column(name = "reason", length = 255, updatable = false)
    private String reason;

    @Column(name = "parcel_version", nullable = false, updatable = false)
    private Long parcelVersion;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    public DeliveryAssignmentAudit() {
    }

    public DeliveryAssignmentAudit(
            UUID auditId,
            UUID parcelId,
            UUID assignmentId,
            String courierSub,
            String action,
            String actorSub,
            String actorRole,
            String previousCourierSub,
            UUID previousAssignmentId,
            String reason,
            Long parcelVersion,
            Instant occurredAt) {
        this.auditId = Objects.requireNonNull(auditId, "auditId cannot be null");
        this.parcelId = Objects.requireNonNull(parcelId, "parcelId cannot be null");
        this.assignmentId = Objects.requireNonNull(assignmentId, "assignmentId cannot be null");
        this.courierSub = Objects.requireNonNull(courierSub, "courierSub cannot be null");
        this.action = Objects.requireNonNull(action, "action cannot be null");
        this.actorSub = Objects.requireNonNull(actorSub, "actorSub cannot be null");
        this.actorRole = Objects.requireNonNull(actorRole, "actorRole cannot be null");
        this.previousCourierSub = previousCourierSub;
        this.previousAssignmentId = previousAssignmentId;
        this.reason = reason;
        this.parcelVersion = Objects.requireNonNull(parcelVersion, "parcelVersion cannot be null");
        this.occurredAt = occurredAt != null ? occurredAt : Instant.now();
    }

    @PrePersist
    protected void onCreate() {
        if (this.occurredAt == null) {
            this.occurredAt = Instant.now();
        }
    }

    public UUID getAuditId() {
        return auditId;
    }

    public void setAuditId(UUID auditId) {
        this.auditId = auditId;
    }

    public UUID getParcelId() {
        return parcelId;
    }

    public void setParcelId(UUID parcelId) {
        this.parcelId = parcelId;
    }

    public UUID getAssignmentId() {
        return assignmentId;
    }

    public void setAssignmentId(UUID assignmentId) {
        this.assignmentId = assignmentId;
    }

    public String getCourierSub() {
        return courierSub;
    }

    public void setCourierSub(String courierSub) {
        this.courierSub = courierSub;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getActorSub() {
        return actorSub;
    }

    public void setActorSub(String actorSub) {
        this.actorSub = actorSub;
    }

    public String getActorRole() {
        return actorRole;
    }

    public void setActorRole(String actorRole) {
        this.actorRole = actorRole;
    }

    public String getPreviousCourierSub() {
        return previousCourierSub;
    }

    public void setPreviousCourierSub(String previousCourierSub) {
        this.previousCourierSub = previousCourierSub;
    }

    public UUID getPreviousAssignmentId() {
        return previousAssignmentId;
    }

    public void setPreviousAssignmentId(UUID previousAssignmentId) {
        this.previousAssignmentId = previousAssignmentId;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public Long getParcelVersion() {
        return parcelVersion;
    }

    public void setParcelVersion(Long parcelVersion) {
        this.parcelVersion = parcelVersion;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(Instant occurredAt) {
        this.occurredAt = occurredAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DeliveryAssignmentAudit that = (DeliveryAssignmentAudit) o;
        return Objects.equals(auditId, that.auditId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(auditId);
    }
}

