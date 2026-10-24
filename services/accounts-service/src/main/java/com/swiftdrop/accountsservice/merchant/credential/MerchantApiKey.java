package com.swiftdrop.accountsservice.merchant.credential;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Entity representing scoped machine API credentials for merchant tenants.
 * Only cryptographic digests and metadata are persisted; plaintext keys are never stored.
 */
@Entity
@Table(name = "merchant_api_keys")
public class MerchantApiKey {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "merchant_id", nullable = false, updatable = false)
    private UUID merchantId;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(name = "key_prefix", nullable = false, updatable = false, length = 32)
    private String keyPrefix;

    @Column(name = "key_digest", nullable = false, updatable = false, length = 255)
    private String keyDigest;

    @Column(nullable = false, length = 255)
    private String scopes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "is_revoked", nullable = false)
    private boolean isRevoked;

    protected MerchantApiKey() { }

    public MerchantApiKey(UUID id, UUID merchantId, String name, String keyPrefix,
                         String keyDigest, List<String> scopes, Instant createdAt, Instant expiresAt) {
        this.id = Objects.requireNonNull(id, "Key ID is required");
        this.merchantId = Objects.requireNonNull(merchantId, "Merchant ID is required");
        this.name = Objects.requireNonNull(name, "Key name is required");
        this.keyPrefix = Objects.requireNonNull(keyPrefix, "Key prefix is required");
        this.keyDigest = Objects.requireNonNull(keyDigest, "Key digest is required");
        this.scopes = String.join(",", Objects.requireNonNull(scopes, "Scopes are required"));
        this.createdAt = Objects.requireNonNull(createdAt, "Created at is required");
        this.expiresAt = Objects.requireNonNull(expiresAt, "Expires at is required");
        this.isRevoked = false;
        this.revokedAt = null;
    }

    public UUID getId() { return id; }
    public UUID getMerchantId() { return merchantId; }
    public String getName() { return name; }
    public String getKeyPrefix() { return keyPrefix; }
    public String getKeyDigest() { return keyDigest; }
    public List<String> getScopes() {
        if (scopes == null || scopes.isBlank()) return Collections.emptyList();
        return Arrays.stream(scopes.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getRevokedAt() { return revokedAt; }
    public boolean isRevoked() { return isRevoked; }

    public boolean isExpired(Instant now) {
        return now.isAfter(expiresAt);
    }

    public boolean isActive(Instant now) {
        return !isRevoked && !isExpired(now);
    }

    public void revoke(Instant now) {
        this.isRevoked = true;
        this.revokedAt = Objects.requireNonNull(now, "Revocation timestamp is required");
    }
}
