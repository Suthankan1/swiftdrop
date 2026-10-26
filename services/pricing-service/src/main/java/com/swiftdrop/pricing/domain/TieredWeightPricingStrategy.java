package com.swiftdrop.pricing.domain;

import com.swiftdrop.pricing.error.AmbiguousRateRuleException;
import com.swiftdrop.pricing.error.InactiveZoneException;
import com.swiftdrop.pricing.error.RateRuleNotFoundException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * Deterministic tiered pricing strategy based on origin/destination zones,
 * parcel size category, chargeable weight, and versioned tariff rules.
 */
public class TieredWeightPricingStrategy implements PricingStrategy {

    @Override
    public PricingCalculation calculate(RateVersion rateVersion,
                                        PricingZone pickupZone,
                                        PricingZone dropoffZone,
                                        ParcelMeasurements measurements,
                                        List<RateRule> availableRules) {
        Objects.requireNonNull(rateVersion, "rateVersion cannot be null");
        Objects.requireNonNull(pickupZone, "pickupZone cannot be null");
        Objects.requireNonNull(dropoffZone, "dropoffZone cannot be null");
        Objects.requireNonNull(measurements, "measurements cannot be null");
        Objects.requireNonNull(availableRules, "availableRules cannot be null");

        if (!pickupZone.isActive()) {
            throw new InactiveZoneException(pickupZone.getZoneCode());
        }
        if (!dropoffZone.isActive()) {
            throw new InactiveZoneException(dropoffZone.getZoneCode());
        }

        BigDecimal chargeableWeight = measurements.getChargeableWeightKg();
        SizeCategory sizeCategory = measurements.getSizeCategory();

        List<RateRule> matchingRules = availableRules.stream()
                .filter(r -> r.getPickupZoneCode().equalsIgnoreCase(pickupZone.getZoneCode()))
                .filter(r -> r.getDropoffZoneCode().equalsIgnoreCase(dropoffZone.getZoneCode()))
                .filter(r -> r.getSizeCategory() == sizeCategory)
                .filter(r -> r.matchesWeight(chargeableWeight))
                .toList();

        if (matchingRules.isEmpty()) {
            throw new RateRuleNotFoundException(
                    "No matching rate rule found for route " + pickupZone.getZoneCode() + " -> " + dropoffZone.getZoneCode()
                            + ", size=" + sizeCategory + ", chargeableWeight=" + chargeableWeight + "kg"
                            + " in rate version " + rateVersion.getVersionCode());
        }

        if (matchingRules.size() > 1) {
            throw new AmbiguousRateRuleException(
                    "Multiple (" + matchingRules.size() + ") overlapping rate rules match route "
                            + pickupZone.getZoneCode() + " -> " + dropoffZone.getZoneCode()
                            + ", size=" + sizeCategory + ", chargeableWeight=" + chargeableWeight + "kg"
                            + " in rate version " + rateVersion.getVersionCode());
        }

        RateRule rule = matchingRules.getFirst();

        Money baseFee = rule.getBaseFee();
        Money weightSurcharge = rule.calculateWeightSurcharge(chargeableWeight);
        Money totalAmount = rule.calculateTotalFee(chargeableWeight);

        return new PricingCalculation(
                rateVersion.getVersionCode(),
                rateVersion.getCurrency(),
                pickupZone.getZoneCode(),
                dropoffZone.getZoneCode(),
                measurements.getPhysicalWeightKg(),
                measurements.getVolumetricWeightKg(),
                measurements.getChargeableWeightKg(),
                measurements.getSizeCategory(),
                baseFee,
                weightSurcharge,
                totalAmount,
                rule.getId()
        );
    }
}
