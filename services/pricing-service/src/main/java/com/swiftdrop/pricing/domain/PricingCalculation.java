package com.swiftdrop.pricing.domain;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable calculation result detailing all components of an evaluated price.
 *
 * Designed for immediate reuse by Issue #108 (Quote API / Quote Snapshot)
 * and Issue #114 (Quote Verification gRPC provider).
 */
public final class PricingCalculation {

    private final String rateVersion;
    private final String currency;
    private final String pickupZone;
    private final String dropoffZone;
    private final BigDecimal physicalWeightKg;
    private final BigDecimal volumetricWeightKg;
    private final BigDecimal chargeableWeightKg;
    private final SizeCategory sizeCategory;
    private final Money baseFee;
    private final Money weightSurcharge;
    private final Money totalAmount;
    private final UUID evaluatedRuleId;

    public PricingCalculation(String rateVersion,
                              String currency,
                              String pickupZone,
                              String dropoffZone,
                              BigDecimal physicalWeightKg,
                              BigDecimal volumetricWeightKg,
                              BigDecimal chargeableWeightKg,
                              SizeCategory sizeCategory,
                              Money baseFee,
                              Money weightSurcharge,
                              Money totalAmount,
                              UUID evaluatedRuleId) {
        this.rateVersion = Objects.requireNonNull(rateVersion, "rateVersion cannot be null");
        this.currency = Objects.requireNonNull(currency, "currency cannot be null");
        this.pickupZone = Objects.requireNonNull(pickupZone, "pickupZone cannot be null");
        this.dropoffZone = Objects.requireNonNull(dropoffZone, "dropoffZone cannot be null");
        this.physicalWeightKg = Objects.requireNonNull(physicalWeightKg, "physicalWeightKg cannot be null");
        this.volumetricWeightKg = Objects.requireNonNull(volumetricWeightKg, "volumetricWeightKg cannot be null");
        this.chargeableWeightKg = Objects.requireNonNull(chargeableWeightKg, "chargeableWeightKg cannot be null");
        this.sizeCategory = Objects.requireNonNull(sizeCategory, "sizeCategory cannot be null");
        this.baseFee = Objects.requireNonNull(baseFee, "baseFee cannot be null");
        this.weightSurcharge = Objects.requireNonNull(weightSurcharge, "weightSurcharge cannot be null");
        this.totalAmount = Objects.requireNonNull(totalAmount, "totalAmount cannot be null");
        this.evaluatedRuleId = evaluatedRuleId;
    }

    public String getRateVersion() {
        return rateVersion;
    }

    public String getCurrency() {
        return currency;
    }

    public String getPickupZone() {
        return pickupZone;
    }

    public String getDropoffZone() {
        return dropoffZone;
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

    public Money getBaseFee() {
        return baseFee;
    }

    public long getBaseFeeMinorUnits() {
        return baseFee.getAmountMinorUnits();
    }

    public Money getWeightSurcharge() {
        return weightSurcharge;
    }

    public long getWeightSurchargeMinorUnits() {
        return weightSurcharge.getAmountMinorUnits();
    }

    public Money getTotalAmount() {
        return totalAmount;
    }

    public long getTotalAmountMinorUnits() {
        return totalAmount.getAmountMinorUnits();
    }

    public UUID getEvaluatedRuleId() {
        return evaluatedRuleId;
    }

    @Override
    public String toString() {
        return "PricingCalculation{" +
                "rateVersion='" + rateVersion + '\'' +
                ", currency='" + currency + '\'' +
                ", route=" + pickupZone + "->" + dropoffZone +
                ", chargeableWeight=" + chargeableWeightKg + "kg" +
                ", sizeCategory=" + sizeCategory +
                ", base=" + baseFee +
                ", surcharge=" + weightSurcharge +
                ", total=" + totalAmount +
                '}';
    }
}
