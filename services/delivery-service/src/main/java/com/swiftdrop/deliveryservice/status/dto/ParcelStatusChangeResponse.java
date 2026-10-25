package com.swiftdrop.deliveryservice.status.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Response payload emitted upon a successful parcel status transition.
 *
 * Implements REST contract schema ParcelStatusChangeResponse in openapi-delivery.yaml.
 */
public record ParcelStatusChangeResponse(
        UUID parcelId,
        String trackingNumber,
        String previousStatus,
        String currentStatus,
        Long stateVersion,
        Long updatedParcelVersion,
        Instant occurredAt
) {
}
