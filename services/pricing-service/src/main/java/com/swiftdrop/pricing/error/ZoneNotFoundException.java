package com.swiftdrop.pricing.error;

/**
 * Thrown when an unknown pricing zone code is specified.
 */
public class ZoneNotFoundException extends PricingDomainException {

    public ZoneNotFoundException(String zoneCode) {
        super("ZONE_NOT_FOUND", "Pricing zone not found: " + zoneCode);
    }
}
