package com.swiftdrop.deliveryservice.quote;
import java.time.Instant;
import java.util.UUID;
/** Field-for-field local representation of the existing Pricing ValidateQuoteResponse. */
public record QuoteValidation(boolean isValid, UUID quoteId, String rateVersion,
 long verifiedAmountMinorUnits, String currency, Instant expiresAt, String validationCode,
 String pickupZone, String dropoffZone, String pickupZoneName, String dropoffZoneName,
 java.math.BigDecimal weightKg, String sizeCategory, String dimensionsCm) {

    public QuoteValidation(boolean isValid, UUID quoteId, String rateVersion, long verifiedAmountMinorUnits,
                           String currency, Instant expiresAt, String validationCode) {
        this(isValid, quoteId, rateVersion, verifiedAmountMinorUnits, currency, expiresAt, validationCode,
                null, null, null, null, null, null, null);
    }
}
