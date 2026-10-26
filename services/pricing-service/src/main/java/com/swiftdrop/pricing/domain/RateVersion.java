package com.swiftdrop.pricing.domain;

import com.swiftdrop.pricing.error.RateVersionImmutableException;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Domain entity representing an immutable versioned rate card.
 *
 * Implements DEC-PRC-06 (immutable version strings, rate cards are strictly versioned).
 * Published versions cannot be mutated.
 */
public final class RateVersion {

    private final UUID id;
    private final String versionCode;
    private final String currency;
    private RateVersionStatus status;
    private final Instant effectiveFrom;
    private Instant effectiveTo;
    private final String description;
    private final Instant createdAt;
    private Instant activatedAt;
    private Instant archivedAt;

    public RateVersion(UUID id,
                       String versionCode,
                       String currency,
                       RateVersionStatus status,
                       Instant effectiveFrom,
                       Instant effectiveTo,
                       String description,
                       Instant createdAt,
                       Instant activatedAt,
                       Instant archivedAt) {
        this.id = id;
        this.versionCode = Objects.requireNonNull(versionCode, "versionCode cannot be null");
        this.currency = Objects.requireNonNull(currency, "currency cannot be null");
        this.status = Objects.requireNonNull(status, "status cannot be null");
        this.effectiveFrom = Objects.requireNonNull(effectiveFrom, "effectiveFrom cannot be null");
        this.effectiveTo = effectiveTo;
        this.description = description;
        this.createdAt = createdAt != null ? createdAt : Instant.now();
        this.activatedAt = activatedAt;
        this.archivedAt = archivedAt;
    }

    public UUID getId() {
        return id;
    }

    public String getVersionCode() {
        return versionCode;
    }

    public String getCurrency() {
        return currency;
    }

    public RateVersionStatus getStatus() {
        return status;
    }

    public Instant getEffectiveFrom() {
        return effectiveFrom;
    }

    public Instant getEffectiveTo() {
        return effectiveTo;
    }

    public String getDescription() {
        return description;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getActivatedAt() {
        return activatedAt;
    }

    public Instant getArchivedAt() {
        return archivedAt;
    }

    public boolean isActive() {
        return status == RateVersionStatus.ACTIVE;
    }

    public boolean isEffectiveAt(Instant timestamp) {
        if (!isActive()) {
            return false;
        }
        if (timestamp.isBefore(effectiveFrom)) {
            return false;
        }
        return effectiveTo == null || !timestamp.isAfter(effectiveTo);
    }

    /**
     * Ensures that rate rules can only be added or modified while the version is in DRAFT status.
     * Throws RateVersionImmutableException if the version is already published or active.
     */
    public void ensureMutable() {
        if (!status.canMutateRules()) {
            throw new RateVersionImmutableException(
                    "Rate version " + versionCode + " is " + status + " and cannot be mutated.");
        }
    }

    public void activate(Instant at) {
        if (status == RateVersionStatus.ARCHIVED) {
            throw new RateVersionImmutableException("Cannot activate an archived rate version: " + versionCode);
        }
        this.status = RateVersionStatus.ACTIVE;
        this.activatedAt = at;
    }

    public void supersede(Instant at) {
        this.status = RateVersionStatus.SUPERSEDED;
        this.effectiveTo = at;
    }

    public void archive(Instant at) {
        this.status = RateVersionStatus.ARCHIVED;
        this.archivedAt = at;
        if (this.effectiveTo == null) {
            this.effectiveTo = at;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RateVersion that = (RateVersion) o;
        return Objects.equals(versionCode, that.versionCode);
    }

    @Override
    public int hashCode() {
        return Objects.hash(versionCode);
    }

    @Override
    public String toString() {
        return "RateVersion{" +
                "versionCode='" + versionCode + '\'' +
                ", currency='" + currency + '\'' +
                ", status=" + status +
                ", effectiveFrom=" + effectiveFrom +
                ", effectiveTo=" + effectiveTo +
                '}';
    }
}
