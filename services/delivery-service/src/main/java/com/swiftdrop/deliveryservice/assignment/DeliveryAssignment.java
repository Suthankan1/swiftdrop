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
 * Delivery Courier Assignment entity mapping to delivery_db.delivery_assignments.
 *
 * Implements DEC-ASG-01 through DEC-ASG-07 for single-active-courier assignment tracking.
 */
@Entity
@Table(name = "delivery_assignments")
public class DeliveryAssignment {

    @Id
    @Column(name = "assignment_id", nullable = false, updatable = false)
    private UUID assignmentId;

    @Column(name = "parcel_id", nullable = false, updatable = false)
    private UUID parcelId;

    @Column(name = "courier_sub", nullable = false, length = 64, updatable = false)
    private String courierSub;

    @Column(name = "assigned_by", nullable = false, length = 64, updatable = false)
    private String assignedBy;

    @Column(name = "assigned_at", nullable = false, updatable = false)
    private Instant assignedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    public DeliveryAssignment() {
    }

    public DeliveryAssignment(UUID assignmentId,
                              UUID parcelId,
                              String courierSub,
                              String assignedBy,
                              Instant assignedAt,
                              Instant endedAt,
                              boolean active) {
        this.assignmentId = Objects.requireNonNull(assignmentId, "assignmentId cannot be null");
        this.parcelId = Objects.requireNonNull(parcelId, "parcelId cannot be null");
        this.courierSub = Objects.requireNonNull(courierSub, "courierSub cannot be null");
        this.assignedBy = Objects.requireNonNull(assignedBy, "assignedBy cannot be null");
        this.assignedAt = assignedAt != null ? assignedAt : Instant.now();
        this.endedAt = endedAt;
        this.active = active;
    }

    @PrePersist
    protected void onCreate() {
        if (this.assignedAt == null) {
            this.assignedAt = Instant.now();
        }
    }

    // Getters and Setters

    public UUID getAssignmentId() {
        return assignmentId;
    }

    public void setAssignmentId(UUID assignmentId) {
        this.assignmentId = assignmentId;
    }

    public UUID getParcelId() {
        return parcelId;
    }

    public void setParcelId(UUID parcelId) {
        this.parcelId = parcelId;
    }

    public String getCourierSub() {
        return courierSub;
    }

    public void setCourierSub(String courierSub) {
        this.courierSub = courierSub;
    }

    public String getAssignedBy() {
        return assignedBy;
    }

    public void setAssignedBy(String assignedBy) {
        this.assignedBy = assignedBy;
    }

    public Instant getAssignedAt() {
        return assignedAt;
    }

    public void setAssignedAt(Instant assignedAt) {
        this.assignedAt = assignedAt;
    }

    public Instant getEndedAt() {
        return endedAt;
    }

    public void setEndedAt(Instant endedAt) {
        this.endedAt = endedAt;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DeliveryAssignment that = (DeliveryAssignment) o;
        return Objects.equals(assignmentId, that.assignmentId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(assignmentId);
    }
}
