package com.swiftdrop.pricing.error;

/**
 * Thrown when an attempt is made to mutate or overwrite a published or active rate version.
 */
public class RateVersionImmutableException extends PricingDomainException {

    public RateVersionImmutableException(String message) {
        super("RATE_VERSION_IMMUTABLE", message);
    }
}
