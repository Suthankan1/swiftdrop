package com.swiftdrop.pricing.domain;

import java.util.List;

/**
 * Strategy interface for calculating parcel delivery prices deterministically.
 */
public interface PricingStrategy {

    PricingCalculation calculate(RateVersion rateVersion,
                                 PricingZone pickupZone,
                                 PricingZone dropoffZone,
                                 ParcelMeasurements measurements,
                                 List<RateRule> availableRules);
}
