package com.swiftdrop.pricing.error;

/**
 * Thrown when no matching rate rule covers the given origin, destination, size category, and weight band.
 */
public class RateRuleNotFoundException extends PricingDomainException {

    public RateRuleNotFoundException(String message) {
        super("RATE_RULE_NOT_FOUND", message);
    }
}
