package com.swiftdrop.pricing.persistence;

import com.swiftdrop.pricing.domain.Money;
import com.swiftdrop.pricing.domain.RateRule;
import com.swiftdrop.pricing.domain.SizeCategory;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "pricing_rate_rules")
public class RateRuleEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "rate_version_id", nullable = false)
    private UUID rateVersionId;

    @Column(name = "pickup_zone_code", nullable = false, length = 32)
    private String pickupZoneCode;

    @Column(name = "dropoff_zone_code", nullable = false, length = 32)
    private String dropoffZoneCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "size_category", nullable = false, length = 32)
    private SizeCategory sizeCategory;

    @Column(name = "min_weight_kg", nullable = false, precision = 6, scale = 2)
    private BigDecimal minWeightKg;

    @Column(name = "max_weight_kg", nullable = false, precision = 6, scale = 2)
    private BigDecimal maxWeightKg;

    @Column(name = "base_fee_minor_units", nullable = false)
    private long baseFeeMinorUnits;

    @Column(name = "per_kg_surcharge_minor_units", nullable = false)
    private long perKgSurchargeMinorUnits;

    @Column(name = "weight_threshold_kg", nullable = false, precision = 6, scale = 2)
    private BigDecimal weightThresholdKg = BigDecimal.ZERO;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected RateRuleEntity() {
    }

    public RateRuleEntity(UUID rateVersionId,
                          String pickupZoneCode,
                          String dropoffZoneCode,
                          SizeCategory sizeCategory,
                          BigDecimal minWeightKg,
                          BigDecimal maxWeightKg,
                          long baseFeeMinorUnits,
                          long perKgSurchargeMinorUnits,
                          BigDecimal weightThresholdKg) {
        this(UUID.randomUUID(), rateVersionId, pickupZoneCode, dropoffZoneCode, sizeCategory,
                minWeightKg, maxWeightKg, baseFeeMinorUnits, perKgSurchargeMinorUnits, weightThresholdKg);
    }

    public RateRuleEntity(UUID id,
                          UUID rateVersionId,
                          String pickupZoneCode,
                          String dropoffZoneCode,
                          SizeCategory sizeCategory,
                          BigDecimal minWeightKg,
                          BigDecimal maxWeightKg,
                          long baseFeeMinorUnits,
                          long perKgSurchargeMinorUnits,
                          BigDecimal weightThresholdKg) {
        this.id = id;
        this.rateVersionId = rateVersionId;
        this.pickupZoneCode = pickupZoneCode;
        this.dropoffZoneCode = dropoffZoneCode;
        this.sizeCategory = sizeCategory;
        this.minWeightKg = minWeightKg;
        this.maxWeightKg = maxWeightKg;
        this.baseFeeMinorUnits = baseFeeMinorUnits;
        this.perKgSurchargeMinorUnits = perKgSurchargeMinorUnits;
        this.weightThresholdKg = weightThresholdKg != null ? weightThresholdKg : BigDecimal.ZERO;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getRateVersionId() {
        return rateVersionId;
    }

    public void setRateVersionId(UUID rateVersionId) {
        this.rateVersionId = rateVersionId;
    }

    public String getPickupZoneCode() {
        return pickupZoneCode;
    }

    public void setPickupZoneCode(String pickupZoneCode) {
        this.pickupZoneCode = pickupZoneCode;
    }

    public String getDropoffZoneCode() {
        return dropoffZoneCode;
    }

    public void setDropoffZoneCode(String dropoffZoneCode) {
        this.dropoffZoneCode = dropoffZoneCode;
    }

    public SizeCategory getSizeCategory() {
        return sizeCategory;
    }

    public void setSizeCategory(SizeCategory sizeCategory) {
        this.sizeCategory = sizeCategory;
    }

    public BigDecimal getMinWeightKg() {
        return minWeightKg;
    }

    public void setMinWeightKg(BigDecimal minWeightKg) {
        this.minWeightKg = minWeightKg;
    }

    public BigDecimal getMaxWeightKg() {
        return maxWeightKg;
    }

    public void setMaxWeightKg(BigDecimal maxWeightKg) {
        this.maxWeightKg = maxWeightKg;
    }

    public long getBaseFeeMinorUnits() {
        return baseFeeMinorUnits;
    }

    public void setBaseFeeMinorUnits(long baseFeeMinorUnits) {
        this.baseFeeMinorUnits = baseFeeMinorUnits;
    }

    public long getPerKgSurchargeMinorUnits() {
        return perKgSurchargeMinorUnits;
    }

    public void setPerKgSurchargeMinorUnits(long perKgSurchargeMinorUnits) {
        this.perKgSurchargeMinorUnits = perKgSurchargeMinorUnits;
    }

    public BigDecimal getWeightThresholdKg() {
        return weightThresholdKg;
    }

    public void setWeightThresholdKg(BigDecimal weightThresholdKg) {
        this.weightThresholdKg = weightThresholdKg;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public RateRule toDomain(String currency) {
        return new RateRule(
                id,
                rateVersionId,
                pickupZoneCode,
                dropoffZoneCode,
                sizeCategory,
                minWeightKg,
                maxWeightKg,
                Money.ofMinorUnits(baseFeeMinorUnits, currency),
                Money.ofMinorUnits(perKgSurchargeMinorUnits, currency),
                weightThresholdKg
        );
    }
}
