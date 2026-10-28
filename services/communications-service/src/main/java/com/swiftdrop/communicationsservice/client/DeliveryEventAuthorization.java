package com.swiftdrop.communicationsservice.client;

import java.util.Objects;
import java.util.UUID;

/**
 * Domain representation of Delivery event-to-recipient authorization outcome (IF-GRPC-11, #111).
 * Carries minimal approved recipient/merchant references with zero contact PII.
 */
public record DeliveryEventAuthorization(
        boolean isAuthorized,
        UUID parcelId,
        UUID eventId,
        String recipientSub,
        UUID merchantId,
        String trackingNumber,
        String currentStatus,
        String eventType,
        long eventVersion,
        long currentVersion,
        boolean isTerminal,
        boolean isStale,
        String authorizationReason
) {
    public DeliveryEventAuthorization {
        Objects.requireNonNull(parcelId, "parcelId is required");
        Objects.requireNonNull(eventId, "eventId is required");
        authorizationReason = authorizationReason != null ? authorizationReason : "";
        trackingNumber = trackingNumber != null ? trackingNumber : "";
        currentStatus = currentStatus != null ? currentStatus : "";
        eventType = eventType != null ? eventType : "";
        recipientSub = recipientSub != null ? recipientSub : "";
    }
}
