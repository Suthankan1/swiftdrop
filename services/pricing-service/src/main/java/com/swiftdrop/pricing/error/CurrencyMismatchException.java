package com.swiftdrop.pricing.error;

/**
 * Thrown when attempting an arithmetic or rate operation across incompatible currencies.
 */
public class CurrencyMismatchException extends PricingDomainException {

    public CurrencyMismatchException(String message) {
        super("CURRENCY_MISMATCH", message);
    }
}
