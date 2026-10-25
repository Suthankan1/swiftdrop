package com.swiftdrop.pricing.persistence;

import com.swiftdrop.pricing.domain.RateVersion;
import com.swiftdrop.pricing.domain.RateVersionStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "pricing_rate_versions")
public class RateVersionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "version_code", nullable = false, unique = true, length = 64)
    private String versionCode;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private RateVersionStatus status;

    @Column(name = "effective_from", nullable = false)
    private Instant effectiveFrom;

    @Column(name = "effective_to")
    private Instant effectiveTo;

    @Column(name = "description", length = 512)
    private String description;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "activated_at")
    private Instant activatedAt;

    @Column(name = "archived_at")
    private Instant archivedAt;

    protected RateVersionEntity() {
    }

    public RateVersionEntity(String versionCode,
                             String currency,
                             RateVersionStatus status,
                             Instant effectiveFrom,
                             String description) {
        this(UUID.randomUUID(), versionCode, currency, status, effectiveFrom, description);
    }

    public RateVersionEntity(UUID id,
                             String versionCode,
                             String currency,
                             RateVersionStatus status,
                             Instant effectiveFrom,
                             String description) {
        this.id = id;
        this.versionCode = versionCode;
        this.currency = currency;
        this.status = status;
        this.effectiveFrom = effectiveFrom;
        this.description = description;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getVersionCode() {
        return versionCode;
    }

    public void setVersionCode(String versionCode) {
        this.versionCode = versionCode;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public RateVersionStatus getStatus() {
        return status;
    }

    public void setStatus(RateVersionStatus status) {
        this.status = status;
    }

    public Instant getEffectiveFrom() {
        return effectiveFrom;
    }

    public void setEffectiveFrom(Instant effectiveFrom) {
        this.effectiveFrom = effectiveFrom;
    }

    public Instant getEffectiveTo() {
        return effectiveTo;
    }

    public void setEffectiveTo(Instant effectiveTo) {
        this.effectiveTo = effectiveTo;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getActivatedAt() {
        return activatedAt;
    }

    public void setActivatedAt(Instant activatedAt) {
        this.activatedAt = activatedAt;
    }

    public Instant getArchivedAt() {
        return archivedAt;
    }

    public void setArchivedAt(Instant archivedAt) {
        this.archivedAt = archivedAt;
    }

    public RateVersion toDomain() {
        return new RateVersion(
                id,
                versionCode,
                currency,
                status,
                effectiveFrom,
                effectiveTo,
                description,
                createdAt,
                activatedAt,
                archivedAt
        );
    }
}
