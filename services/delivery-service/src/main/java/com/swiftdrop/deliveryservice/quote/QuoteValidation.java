package com.swiftdrop.deliveryservice.quote;
import java.time.Instant;
import java.util.UUID;
/** Field-for-field local representation of the existing Pricing ValidateQuoteResponse. */
public record QuoteValidation(boolean isValid, UUID quoteId, String rateVersion,
 long verifiedAmountMinorUnits, String currency, Instant expiresAt, String validationCode) {}
