package com.swiftdrop.pricing.domain;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable quote snapshot representing an authoritative price quotation at issuance time.
 *
 * Adheres to DEC-PRC-01 to DEC-PRC-08:
 * - Deterministic integer minor units
 * - Exact measurement capture
 * - Fixed configurable expiry lifetime (default 15 minutes)
 * - Explicit immutable state that preserves the tariff at issuance time even if active rates change
 */
public final class QuoteSnapshot {

    private final UUID quoteId;
    private final String rateVersion;
    private final UUID rateVersionId;
    private final UUID rateRuleId;
    private final String pickupZone;
    private final String dropoffZone;
    private final int lengthCm;
    private final int widthCm;
    private final int heightCm;
    private final BigDecimal physicalWeightKg;
    private final BigDecimal volumetricWeightKg;
    private final BigDecimal chargeableWeightKg;
    private final SizeCategory sizeCategory;
    private final long baseFeeMinorUnits;
    private final long weightSurchargeMinorUnits;
    private final long totalAmountMinorUnits;
    private final String currency;
    private final String subjectSub;
    private final String tenantId;
    private final boolean isAnonymous;
    private final Instant createdAt;
    private final Instant expiresAt;

    public QuoteSnapshot(UUID quoteId,
                         String rateVersion,
                         UUID rateVersionId,
                         UUID rateRuleId,
                         String pickupZone,
                         String dropoffZone,
                         int lengthCm,
                         int widthCm,
                         int heightCm,
                         BigDecimal physicalWeightKg,
                         BigDecimal volumetricWeightKg,
                         BigDecimal chargeableWeightKg,
                         SizeCategory sizeCategory,
                         long baseFeeMinorUnits,
                         long weightSurchargeMinorUnits,
                         long totalAmountMinorUnits,
                         String currency,
                         String subjectSub,
                         String tenantId,
                         boolean isAnonymous,
                         Instant createdAt,
                         Instant expiresAt) {
        this.quoteId = Objects.requireNonNull(quoteId, "quoteId cannot be null");
        this.rateVersion = Objects.requireNonNull(rateVersion, "rateVersion cannot be null");
        this.rateVersionId = Objects.requireNonNull(rateVersionId, "rateVersionId cannot be null");
        this.rateRuleId = rateRuleId;
        this.pickupZone = Objects.requireNonNull(pickupZone, "pickupZone cannot be null");
        this.dropoffZone = Objects.requireNonNull(dropoffZone, "dropoffZone cannot be null");
        this.lengthCm = lengthCm;
        this.widthCm = widthCm;
        this.heightCm = heightCm;
        this.physicalWeightKg = Objects.requireNonNull(physicalWeightKg, "physicalWeightKg cannot be null");
        this.volumetricWeightKg = Objects.requireNonNull(volumetricWeightKg, "volumetricWeightKg cannot be null");
        this.chargeableWeightKg = Objects.requireNonNull(chargeableWeightKg, "chargeableWeightKg cannot be null");
        this.sizeCategory = Objects.requireNonNull(sizeCategory, "sizeCategory cannot be null");
        if (baseFeeMinorUnits < 0 || weightSurchargeMinorUnits < 0 || totalAmountMinorUnits < 0) {
            throw new IllegalArgumentException("Monetary fees cannot be negative");
        }
        this.baseFeeMinorUnits = baseFeeMinorUnits;
        this.weightSurchargeMinorUnits = weightSurchargeMinorUnits;
        this.totalAmountMinorUnits = totalAmountMinorUnits;
        this.currency = Objects.requireNonNull(currency, "currency cannot be null");
        this.isAnonymous = isAnonymous;
        if (isAnonymous) {
            if (subjectSub != null && !subjectSub.isBlank()) {
                throw new IllegalArgumentException("Anonymous quote cannot have a bound subjectSub");
            }
            this.subjectSub = null;
        } else {
            if (subjectSub == null || subjectSub.isBlank()) {
                throw new IllegalArgumentException("Authenticated quote must have a bound subjectSub");
            }
            this.subjectSub = subjectSub.trim();
        }
        this.tenantId = (tenantId != null && !tenantId.isBlank()) ? tenantId.trim() : null;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt cannot be null");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt cannot be null");
        if (!expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("expiresAt (" + expiresAt + ") must be strictly after createdAt (" + createdAt + ")");
        }
    }

    public UUID getQuoteId() {
        return quoteId;
    }

    public String getRateVersion() {
        return rateVersion;
    }

    public UUID getRateVersionId() {
        return rateVersionId;
    }

    public UUID getRateRuleId() {
        return rateRuleId;
    }

    public String getPickupZone() {
        return pickupZone;
    }

    public String getDropoffZone() {
        return dropoffZone;
    }

    public int getLengthCm() {
        return lengthCm;
    }

    public int getWidthCm() {
        return widthCm;
    }

    public int getHeightCm() {
        return heightCm;
    }

    public BigDecimal getPhysicalWeightKg() {
        return physicalWeightKg;
    }

    public BigDecimal getVolumetricWeightKg() {
        return volumetricWeightKg;
    }

    public BigDecimal getChargeableWeightKg() {
        return chargeableWeightKg;
    }

    public SizeCategory getSizeCategory() {
        return sizeCategory;
    }

    public long getBaseFeeMinorUnits() {
        return baseFeeMinorUnits;
    }

    public long getWeightSurchargeMinorUnits() {
        return weightSurchargeMinorUnits;
    }

    public long getTotalAmountMinorUnits() {
        return totalAmountMinorUnits;
    }

    public String getCurrency() {
        return currency;
    }

    public String getSubjectSub() {
        return subjectSub;
    }

    public String getTenantId() {
        return tenantId;
    }

    public boolean isAnonymous() {
        return isAnonymous;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    /**
     * Evaluates expiry dynamically against the provided Clock.
     * Expiry condition: current time is equal to or later than expiresAt.
     */
    public boolean isExpired(Clock clock) {
        Instant now = Instant.now(Objects.requireNonNull(clock, "clock cannot be null"));
        return !now.isBefore(expiresAt);
    }
}
