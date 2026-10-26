package com.swiftdrop.pricing.domain;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * Domain representation of a specific rate matrix rule.
 * Maps origin-destination zones, size category, and weight band to base fee and surcharges.
 */
public final class RateRule {

    private final UUID id;
    private final UUID rateVersionId;
    private final String pickupZoneCode;
    private final String dropoffZoneCode;
    private final SizeCategory sizeCategory;
    private final BigDecimal minWeightKg;
    private final BigDecimal maxWeightKg;
    private final Money baseFee;
    private final Money perKgSurcharge;
    private final BigDecimal weightThresholdKg;

    public RateRule(UUID id,
                    UUID rateVersionId,
                    String pickupZoneCode,
                    String dropoffZoneCode,
                    SizeCategory sizeCategory,
                    BigDecimal minWeightKg,
                    BigDecimal maxWeightKg,
                    Money baseFee,
                    Money perKgSurcharge,
                    BigDecimal weightThresholdKg) {
        this.id = id;
        this.rateVersionId = Objects.requireNonNull(rateVersionId, "rateVersionId cannot be null");
        this.pickupZoneCode = Objects.requireNonNull(pickupZoneCode, "pickupZoneCode cannot be null");
        this.dropoffZoneCode = Objects.requireNonNull(dropoffZoneCode, "dropoffZoneCode cannot be null");
        this.sizeCategory = Objects.requireNonNull(sizeCategory, "sizeCategory cannot be null");
        this.minWeightKg = Objects.requireNonNull(minWeightKg, "minWeightKg cannot be null");
        this.maxWeightKg = Objects.requireNonNull(maxWeightKg, "maxWeightKg cannot be null");
        if (maxWeightKg.compareTo(minWeightKg) < 0) {
            throw new IllegalArgumentException("maxWeightKg cannot be less than minWeightKg");
        }
        this.baseFee = Objects.requireNonNull(baseFee, "baseFee cannot be null");
        this.perKgSurcharge = Objects.requireNonNull(perKgSurcharge, "perKgSurcharge cannot be null");
        if (!baseFee.getCurrency().equals(perKgSurcharge.getCurrency())) {
            throw new IllegalArgumentException("Base fee currency (" + baseFee.getCurrency()
                    + ") and surcharge currency (" + perKgSurcharge.getCurrency() + ") must match");
        }
        this.weightThresholdKg = weightThresholdKg != null ? weightThresholdKg : BigDecimal.ZERO;
    }

    public UUID getId() {
        return id;
    }

    public UUID getRateVersionId() {
        return rateVersionId;
    }

    public String getPickupZoneCode() {
        return pickupZoneCode;
    }

    public String getDropoffZoneCode() {
        return dropoffZoneCode;
    }

    public SizeCategory getSizeCategory() {
        return sizeCategory;
    }

    public BigDecimal getMinWeightKg() {
        return minWeightKg;
    }

    public BigDecimal getMaxWeightKg() {
        return maxWeightKg;
    }

    public Money getBaseFee() {
        return baseFee;
    }

    public Money getPerKgSurcharge() {
        return perKgSurcharge;
    }

    public BigDecimal getWeightThresholdKg() {
        return weightThresholdKg;
    }

    /**
     * Determines whether this rule covers the specified chargeable weight.
     */
    public boolean matchesWeight(BigDecimal chargeableWeightKg) {
        return chargeableWeightKg.compareTo(minWeightKg) >= 0 && chargeableWeightKg.compareTo(maxWeightKg) <= 0;
    }

    /**
     * Calculates the weight surcharge for this rule deterministically.
     */
    public Money calculateWeightSurcharge(BigDecimal chargeableWeightKg) {
        if (chargeableWeightKg.compareTo(weightThresholdKg) <= 0 || perKgSurcharge.getAmountMinorUnits() == 0) {
            return Money.zero(baseFee.getCurrency());
        }
        BigDecimal excessWeight = chargeableWeightKg.subtract(weightThresholdKg);
        return perKgSurcharge.multiply(excessWeight);
    }

    /**
     * Calculates the total fee (base fee + weight surcharge).
     */
    public Money calculateTotalFee(BigDecimal chargeableWeightKg) {
        Money surcharge = calculateWeightSurcharge(chargeableWeightKg);
        return baseFee.plus(surcharge);
    }
}
