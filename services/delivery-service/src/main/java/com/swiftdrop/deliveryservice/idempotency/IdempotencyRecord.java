package com.swiftdrop.deliveryservice.idempotency;

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
 * Idempotency record for API request de-duplication in Delivery Operations.
 *
 * Scoped by principal/tenant and idempotency key to prevent double execution.
 */
@Entity
@Table(name = "idempotency_records")
public class IdempotencyRecord {

    @Id
    @Column(name = "record_id", nullable = false, updatable = false)
    private UUID recordId;

    @Column(name = "principal_or_tenant", nullable = false, length = 64)
    private String principalOrTenant;

    @Column(name = "idempotency_key", nullable = false, length = 64)
    private String idempotencyKey;

    @Column(name = "request_digest", nullable = false, length = 64)
    private String requestDigest;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private IdempotencyStatus status = IdempotencyStatus.IN_PROGRESS;

    @Column(name = "stored_result", columnDefinition = "jsonb")
    private String storedResult;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    public IdempotencyRecord() {
    }

    public IdempotencyRecord(UUID recordId,
                             String principalOrTenant,
                             String idempotencyKey,
                             String requestDigest,
                             IdempotencyStatus status,
                             String storedResult,
                             Instant expiresAt) {
        this.recordId = Objects.requireNonNull(recordId, "recordId cannot be null");
        this.principalOrTenant = Objects.requireNonNull(principalOrTenant, "principalOrTenant cannot be null");
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey cannot be null");
        this.requestDigest = Objects.requireNonNull(requestDigest, "requestDigest cannot be null");
        this.status = status != null ? status : IdempotencyStatus.IN_PROGRESS;
        this.storedResult = storedResult;
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt cannot be null");
    }

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
        if (this.status == null) {
            this.status = IdempotencyStatus.IN_PROGRESS;
        }
    }

    // Getters and Setters

    public UUID getRecordId() {
        return recordId;
    }

    public void setRecordId(UUID recordId) {
        this.recordId = recordId;
    }

    public String getPrincipalOrTenant() {
        return principalOrTenant;
    }

    public void setPrincipalOrTenant(String principalOrTenant) {
        this.principalOrTenant = principalOrTenant;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public String getRequestDigest() {
        return requestDigest;
    }

    public void setRequestDigest(String requestDigest) {
        this.requestDigest = requestDigest;
    }

    public IdempotencyStatus getStatus() {
        return status;
    }

    public void setStatus(IdempotencyStatus status) {
        this.status = status;
    }

    public String getStoredResult() {
        return storedResult;
    }

    public void setStoredResult(String storedResult) {
        this.storedResult = storedResult;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        IdempotencyRecord that = (IdempotencyRecord) o;
        return Objects.equals(recordId, that.recordId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(recordId);
    }
}
