package com.swiftdrop.pricing.error;

/**
 * Thrown when a requested rate version code does not exist.
 */
public class RateVersionNotFoundException extends PricingDomainException {

    public RateVersionNotFoundException(String versionCode) {
        super("RATE_VERSION_NOT_FOUND", "Pricing rate version not found: " + versionCode);
    }
}
