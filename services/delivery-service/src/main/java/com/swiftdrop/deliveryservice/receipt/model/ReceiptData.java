package com.swiftdrop.deliveryservice.receipt.model;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Authoritative, immutable domain model representing validated delivery receipt data.
 * Financial data is sourced from immutable fee snapshots and payment confirmations.
 */
public record ReceiptData(
        String receiptId,
        UUID parcelId,
        String trackingNumber,
        String createdBySub,
        UUID merchantId,
        String recipientName,
        String recipientPhone,
        String pickupAddress,
        String dropoffAddress,
        String deliveryStatus,
        Instant deliveryCompletedAt,
        Instant issuedAt,
        String rateVersion,
        String paymentReference,
        boolean paymentConfirmed,
        String currency,
        long amountMinorUnits,
        List<ReceiptLineItem> lineItems
) {
    public static final String ELIGIBLE_STATUS = "DELIVERED";

    public ReceiptData {
        Objects.requireNonNull(receiptId, "receiptId must not be null");
        Objects.requireNonNull(parcelId, "parcelId must not be null");
        Objects.requireNonNull(trackingNumber, "trackingNumber must not be null");
        Objects.requireNonNull(createdBySub, "createdBySub must not be null");
        Objects.requireNonNull(recipientName, "recipientName must not be null");
        Objects.requireNonNull(recipientPhone, "recipientPhone must not be null");
        Objects.requireNonNull(pickupAddress, "pickupAddress must not be null");
        Objects.requireNonNull(dropoffAddress, "dropoffAddress must not be null");
        Objects.requireNonNull(deliveryStatus, "deliveryStatus must not be null");
        Objects.requireNonNull(deliveryCompletedAt, "deliveryCompletedAt must not be null");
        Objects.requireNonNull(issuedAt, "issuedAt must not be null");
        Objects.requireNonNull(rateVersion, "rateVersion must not be null");
        Objects.requireNonNull(paymentReference, "paymentReference must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        Objects.requireNonNull(lineItems, "lineItems must not be null");

        if (receiptId.isBlank()) throw new IllegalArgumentException("receiptId must not be blank");
        if (createdBySub.isBlank()) throw new IllegalArgumentException("createdBySub must not be blank");
        if (!trackingNumber.matches("^SWD-[0-9]{8}-[A-Z0-9]{4,8}$")) {
            throw new IllegalArgumentException("Invalid tracking number format: " + trackingNumber);
        }
        if (!currency.matches("^[A-Z]{3}$")) {
            throw new IllegalArgumentException("currency must be an ISO-4217 3-letter code: " + currency);
        }
        if (amountMinorUnits < 0) {
            throw new IllegalArgumentException("amountMinorUnits cannot be negative: " + amountMinorUnits);
        }
        if (!paymentConfirmed) {
            throw new IllegalStateException("Receipt cannot be issued for unconfirmed payment");
        }
        if (!ELIGIBLE_STATUS.equalsIgnoreCase(deliveryStatus)) {
            throw new IllegalStateException("Receipt is only available for completed/delivered parcels; current status: " + deliveryStatus);
        }
        if (lineItems.isEmpty()) {
            throw new IllegalArgumentException("Receipt must contain at least one line item");
        }

        // Defensive copy
        lineItems = Collections.unmodifiableList(List.copyOf(lineItems));
    }
}
