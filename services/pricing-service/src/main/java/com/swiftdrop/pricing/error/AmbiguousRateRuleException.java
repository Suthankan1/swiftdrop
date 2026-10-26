package com.swiftdrop.pricing.error;

/**
 * Thrown when multiple overlapping rate rules match the same calculation criteria.
 */
public class AmbiguousRateRuleException extends PricingDomainException {

    public AmbiguousRateRuleException(String message) {
        super("AMBIGUOUS_RATE_RULE", message);
    }
}
