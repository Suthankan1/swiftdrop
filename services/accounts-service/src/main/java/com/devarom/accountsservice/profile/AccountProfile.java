package com.devarom.accountsservice.profile;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.util.UUID;

/** Read-only JPA mapping. Creation goes through the conflict-safe repository insert. */
@Entity
@Immutable
@Table(name = "account_profiles")
public class AccountProfile {
    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;
    @Column(name = "keycloak_sub", nullable = false, updatable = false, length = 255)
    private String keycloakSub;
    @Column(nullable = false, length = 320)
    private String email;
    @Column(name = "full_name", nullable = false, length = 255)
    private String fullName;
    @Column(name = "phone_number", nullable = false, length = 50)
    private String phoneNumber;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AccountProfile() { }

    public UUID getId() { return id; }
    public String getKeycloakSub() { return keycloakSub; }
    public String getEmail() { return email; }
    public String getFullName() { return fullName; }
    public String getPhoneNumber() { return phoneNumber; }
    public Instant getCreatedAt() { return createdAt; }
}
