package com.swiftdrop.deliveryservice.receipt.model;

import java.util.Objects;

/**
 * An immutable financial line item on a delivery receipt.
 */
public record ReceiptLineItem(
        String description,
        long amountMinorUnits,
        String currency
) {
    public ReceiptLineItem {
        Objects.requireNonNull(description, "Line item description must not be null");
        if (description.isBlank()) {
            throw new IllegalArgumentException("Line item description must not be blank");
        }
        if (amountMinorUnits < 0) {
            throw new IllegalArgumentException("Line item amount must not be negative: " + amountMinorUnits);
        }
        Objects.requireNonNull(currency, "Line item currency must not be null");
        if (!currency.matches("^[A-Z]{3}$")) {
            throw new IllegalArgumentException("Line item currency must be an ISO-4217 3-letter code: " + currency);
        }
    }
}
