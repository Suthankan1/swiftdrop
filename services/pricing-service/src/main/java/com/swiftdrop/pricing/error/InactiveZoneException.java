package com.swiftdrop.pricing.error;

/**
 * Thrown when a specified pricing zone is currently inactive or suspended.
 */
public class InactiveZoneException extends PricingDomainException {

    public InactiveZoneException(String zoneCode) {
        super("ZONE_INACTIVE", "Pricing zone is currently inactive: " + zoneCode);
    }
}
