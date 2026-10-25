package com.swiftdrop.pricing.error;

/**
 * Base domain exception for all Pricing & Zones operations.
 */
public class PricingDomainException extends RuntimeException {

    private final String errorCode;

    public PricingDomainException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public PricingDomainException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
