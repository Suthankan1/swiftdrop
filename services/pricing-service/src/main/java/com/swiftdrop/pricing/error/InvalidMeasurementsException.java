package com.swiftdrop.pricing.error;

/**
 * Thrown when parcel dimensions, girth, or physical/volumetric weight violate business bounds.
 */
public class InvalidMeasurementsException extends PricingDomainException {

    public InvalidMeasurementsException(String message) {
        super("INVALID_MEASUREMENTS", message);
    }
}
