package com.swiftdrop.pricing.persistence;

import com.swiftdrop.pricing.domain.QuoteSnapshot;
import com.swiftdrop.pricing.domain.SizeCategory;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * JPA Entity mapping the immutable pricing_quotes table in pricing_db.
 *
 * All fields are non-updatable after initial insert to guarantee snapshot immutability.
 */
@Entity
@Table(name = "pricing_quotes")
public class QuoteEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "rate_version_id", nullable = false, updatable = false)
    private UUID rateVersionId;

    @Column(name = "rate_version_code", nullable = false, updatable = false, length = 64)
    private String rateVersionCode;

    @Column(name = "rate_rule_id", updatable = false)
    private UUID rateRuleId;

    @Column(name = "pickup_zone_code", nullable = false, updatable = false, length = 32)
    private String pickupZoneCode;

    @Column(name = "dropoff_zone_code", nullable = false, updatable = false, length = 32)
    private String dropoffZoneCode;

    @Column(name = "length_cm", nullable = false, updatable = false)
    private int lengthCm;

    @Column(name = "width_cm", nullable = false, updatable = false)
    private int widthCm;

    @Column(name = "height_cm", nullable = false, updatable = false)
    private int heightCm;

    @Column(name = "physical_weight_kg", nullable = false, updatable = false, precision = 6, scale = 2)
    private BigDecimal physicalWeightKg;

    @Column(name = "volumetric_weight_kg", nullable = false, updatable = false, precision = 6, scale = 2)
    private BigDecimal volumetricWeightKg;

    @Column(name = "chargeable_weight_kg", nullable = false, updatable = false, precision = 6, scale = 2)
    private BigDecimal chargeableWeightKg;

    @Enumerated(EnumType.STRING)
    @Column(name = "size_category", nullable = false, updatable = false, length = 32)
    private SizeCategory sizeCategory;

    @Column(name = "base_fee_minor_units", nullable = false, updatable = false)
    private long baseFeeMinorUnits;

    @Column(name = "weight_surcharge_minor_units", nullable = false, updatable = false)
    private long weightSurchargeMinorUnits;

    @Column(name = "total_amount_minor_units", nullable = false, updatable = false)
    private long totalAmountMinorUnits;

    @Column(name = "currency", nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(name = "subject_sub", updatable = false, length = 255)
    private String subjectSub;

    @Column(name = "tenant_id", updatable = false, length = 255)
    private String tenantId;

    @Column(name = "is_anonymous", nullable = false, updatable = false)
    private boolean isAnonymous;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    protected QuoteEntity() {
    }

    public QuoteEntity(UUID id,
                       UUID rateVersionId,
                       String rateVersionCode,
                       UUID rateRuleId,
                       String pickupZoneCode,
                       String dropoffZoneCode,
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
        this.id = Objects.requireNonNull(id, "id cannot be null");
        this.rateVersionId = Objects.requireNonNull(rateVersionId, "rateVersionId cannot be null");
        this.rateVersionCode = Objects.requireNonNull(rateVersionCode, "rateVersionCode cannot be null");
        this.rateRuleId = rateRuleId;
        this.pickupZoneCode = Objects.requireNonNull(pickupZoneCode, "pickupZoneCode cannot be null");
        this.dropoffZoneCode = Objects.requireNonNull(dropoffZoneCode, "dropoffZoneCode cannot be null");
        this.lengthCm = lengthCm;
        this.widthCm = widthCm;
        this.heightCm = heightCm;
        this.physicalWeightKg = Objects.requireNonNull(physicalWeightKg, "physicalWeightKg cannot be null");
        this.volumetricWeightKg = Objects.requireNonNull(volumetricWeightKg, "volumetricWeightKg cannot be null");
        this.chargeableWeightKg = Objects.requireNonNull(chargeableWeightKg, "chargeableWeightKg cannot be null");
        this.sizeCategory = Objects.requireNonNull(sizeCategory, "sizeCategory cannot be null");
        this.baseFeeMinorUnits = baseFeeMinorUnits;
        this.weightSurchargeMinorUnits = weightSurchargeMinorUnits;
        this.totalAmountMinorUnits = totalAmountMinorUnits;
        this.currency = Objects.requireNonNull(currency, "currency cannot be null");
        this.subjectSub = subjectSub;
        this.tenantId = tenantId;
        this.isAnonymous = isAnonymous;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt cannot be null");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt cannot be null");
    }

    public static QuoteEntity fromDomain(QuoteSnapshot snapshot) {
        return new QuoteEntity(
                snapshot.getQuoteId(),
                snapshot.getRateVersionId(),
                snapshot.getRateVersion(),
                snapshot.getRateRuleId(),
                snapshot.getPickupZone(),
                snapshot.getDropoffZone(),
                snapshot.getLengthCm(),
                snapshot.getWidthCm(),
                snapshot.getHeightCm(),
                snapshot.getPhysicalWeightKg(),
                snapshot.getVolumetricWeightKg(),
                snapshot.getChargeableWeightKg(),
                snapshot.getSizeCategory(),
                snapshot.getBaseFeeMinorUnits(),
                snapshot.getWeightSurchargeMinorUnits(),
                snapshot.getTotalAmountMinorUnits(),
                snapshot.getCurrency(),
                snapshot.getSubjectSub(),
                snapshot.getTenantId(),
                snapshot.isAnonymous(),
                snapshot.getCreatedAt(),
                snapshot.getExpiresAt()
        );
    }

    public QuoteSnapshot toDomain() {
        return new QuoteSnapshot(
                id,
                rateVersionCode,
                rateVersionId,
                rateRuleId,
                pickupZoneCode,
                dropoffZoneCode,
                lengthCm,
                widthCm,
                heightCm,
                physicalWeightKg,
                volumetricWeightKg,
                chargeableWeightKg,
                sizeCategory,
                baseFeeMinorUnits,
                weightSurchargeMinorUnits,
                totalAmountMinorUnits,
                currency,
                subjectSub,
                tenantId,
                isAnonymous,
                createdAt,
                expiresAt
        );
    }

    public UUID getId() {
        return id;
    }

    public UUID getRateVersionId() {
        return rateVersionId;
    }

    public String getRateVersionCode() {
        return rateVersionCode;
    }

    public UUID getRateRuleId() {
        return rateRuleId;
    }

    public String getPickupZoneCode() {
        return pickupZoneCode;
    }

    public String getDropoffZoneCode() {
        return dropoffZoneCode;
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
}
