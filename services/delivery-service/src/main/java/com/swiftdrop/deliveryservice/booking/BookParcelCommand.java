package com.swiftdrop.deliveryservice.booking;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Command contract representing intake of a parcel booking request.
 *
 * Implemented and executed under Issue #20.
 */
public record BookParcelCommand(
        String senderSub,
        UUID merchantId,
        UUID quoteId,
        String rateVersion,
        Long amountMinorUnits,
        String currency,
        BigDecimal weightKg,
        String idempotencyKey
) {
    public BookParcelCommand {
        if (senderSub == null || senderSub.isBlank()) {
            throw new IllegalArgumentException("senderSub cannot be blank");
        }
        if (currency == null || currency.isBlank()) {
            currency = "LKR";
        }
    }
}
