package com.swiftdrop.accountsservice.staff;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * JPA entity representing an Accounts-owned staff business profile.
 * Immutably bound to a Keycloak subject identity.
 */
@Entity
@Table(name = "staff_profiles")
public class StaffProfile {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "account_profile_id", updatable = false)
    private UUID accountProfileId;

    @Column(name = "keycloak_sub", nullable = false, updatable = false, length = 255)
    private String keycloakSub;

    @Column(name = "staff_id", nullable = false, length = 50)
    private String staffId;

    @Enumerated(EnumType.STRING)
    @Column(name = "staff_role", nullable = false, length = 30)
    private StaffRole role;

    @Column(nullable = false, length = 100)
    private String department;

    @Column(name = "assigned_zone", length = 100)
    private String assignedZone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private StaffStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private Long version;

    protected StaffProfile() { }

    public StaffProfile(UUID id, UUID accountProfileId, String keycloakSub,
                        String staffId, StaffRole role, String department,
                        String assignedZone, StaffStatus status,
                        Instant createdAt, Instant updatedAt) {
        this.id = Objects.requireNonNull(id, "Staff profile ID is required");
        this.accountProfileId = accountProfileId;
        this.keycloakSub = Objects.requireNonNull(keycloakSub, "Keycloak subject is required");
        this.staffId = Objects.requireNonNull(staffId, "Staff ID is required");
        this.role = Objects.requireNonNull(role, "Staff role is required");
        this.department = Objects.requireNonNull(department, "Department is required");
        this.assignedZone = assignedZone;
        this.status = Objects.requireNonNull(status, "Staff status is required");
        this.createdAt = Objects.requireNonNull(createdAt, "Created at is required");
        this.updatedAt = Objects.requireNonNull(updatedAt, "Updated at is required");
    }

    public UUID getId() {
        return id;
    }

    public UUID getAccountProfileId() {
        return accountProfileId;
    }

    public String getKeycloakSub() {
        return keycloakSub;
    }

    public String getStaffId() {
        return staffId;
    }

    public StaffRole getRole() {
        return role;
    }

    public String getDepartment() {
        return department;
    }

    public String getAssignedZone() {
        return assignedZone;
    }

    public StaffStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Long getVersion() {
        return version;
    }

    public void setStatus(StaffStatus status, Instant updatedAt) {
        this.status = Objects.requireNonNull(status, "Staff status is required");
        this.updatedAt = Objects.requireNonNull(updatedAt, "Updated at is required");
    }

    public void setDepartment(String department, Instant updatedAt) {
        this.department = Objects.requireNonNull(department, "Department is required");
        this.updatedAt = Objects.requireNonNull(updatedAt, "Updated at is required");
    }

    public void setAssignedZone(String assignedZone, Instant updatedAt) {
        this.assignedZone = assignedZone;
        this.updatedAt = Objects.requireNonNull(updatedAt, "Updated at is required");
    }

    public void setAccountProfileId(UUID accountProfileId, Instant updatedAt) {
        this.accountProfileId = accountProfileId;
        this.updatedAt = Objects.requireNonNull(updatedAt, "Updated at is required");
    }
}
