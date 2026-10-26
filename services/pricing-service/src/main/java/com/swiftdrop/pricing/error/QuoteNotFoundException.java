package com.swiftdrop.pricing.error;

import java.util.UUID;

/**
 * Thrown when a requested quote is not found or inaccessible to the caller.
 */
public class QuoteNotFoundException extends PricingDomainException {

    public QuoteNotFoundException(String message) {
        super("QUOTE_NOT_FOUND", message);
    }

    public QuoteNotFoundException(UUID quoteId) {
        super("QUOTE_NOT_FOUND", "Quote not found: " + quoteId);
    }
}
