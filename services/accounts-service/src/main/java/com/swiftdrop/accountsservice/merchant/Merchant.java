package com.swiftdrop.accountsservice.merchant;

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
 * Merchant tenant persistence entity owned by Accounts & Partners Service.
 * Binds an authenticated Keycloak subject and account profile to a business tenant.
 */
@Entity
@Table(name = "merchants")
public class Merchant {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "account_profile_id", nullable = false, updatable = false)
    private UUID accountProfileId;

    @Column(name = "keycloak_sub", nullable = false, updatable = false, length = 255)
    private String keycloakSub;

    @Column(name = "business_name", nullable = false, length = 255)
    private String businessName;

    @Column(name = "registration_number", nullable = false, length = 100)
    private String registrationNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private MerchantStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @jakarta.persistence.Version
    @Column(nullable = false)
    private Long version;

    protected Merchant() { }

    public Merchant(UUID id, UUID accountProfileId, String keycloakSub,
                    String businessName, String registrationNumber,
                    MerchantStatus status, Instant createdAt, Instant updatedAt) {
        this(id, accountProfileId, keycloakSub, businessName, registrationNumber, status, createdAt, updatedAt, 0L);
    }

    public Merchant(UUID id, UUID accountProfileId, String keycloakSub,
                    String businessName, String registrationNumber,
                    MerchantStatus status, Instant createdAt, Instant updatedAt,
                    Long version) {
        this.id = Objects.requireNonNull(id, "Merchant ID is required");
        this.accountProfileId = Objects.requireNonNull(accountProfileId, "Account profile ID is required");
        this.keycloakSub = Objects.requireNonNull(keycloakSub, "Keycloak subject is required");
        this.businessName = Objects.requireNonNull(businessName, "Business name is required");
        this.registrationNumber = Objects.requireNonNull(registrationNumber, "Registration number is required");
        this.status = Objects.requireNonNull(status, "Status is required");
        this.createdAt = Objects.requireNonNull(createdAt, "Created at is required");
        this.updatedAt = Objects.requireNonNull(updatedAt, "Updated at is required");
        this.version = version != null ? version : 0L;
    }

    public UUID getId() { return id; }
    public UUID getAccountProfileId() { return accountProfileId; }
    public String getKeycloakSub() { return keycloakSub; }
    public String getBusinessName() { return businessName; }
    public String getRegistrationNumber() { return registrationNumber; }
    public MerchantStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Long getVersion() { return version; }

    public void setStatus(MerchantStatus status, Instant updatedAt) {
        this.status = Objects.requireNonNull(status, "Status is required");
        this.updatedAt = Objects.requireNonNull(updatedAt, "Updated at is required");
    }
}
