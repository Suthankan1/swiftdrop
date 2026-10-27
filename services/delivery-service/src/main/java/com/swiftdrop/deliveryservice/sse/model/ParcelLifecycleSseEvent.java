package com.swiftdrop.deliveryservice.sse.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable domain event representation delivered over the authenticated SSE channel.
 *
 * Adheres strictly to contracts/events/parcel.status.changed.v1.schema.json and DEC-EVT-03:
 * strictly zero customer contact info, phone numbers, recipient street address,
 * proof URLs, bearer tokens, or internal credentials.
 */
public record ParcelLifecycleSseEvent(
        UUID eventId,
        UUID parcelId,
        String trackingNumber,
        long aggregateVersion,
        String oldStatus,
        String newStatus,
        String reason,
        Instant occurredAt
) {
    public ParcelLifecycleSseEvent {
        Objects.requireNonNull(eventId, "eventId cannot be null");
        Objects.requireNonNull(parcelId, "parcelId cannot be null");
        Objects.requireNonNull(trackingNumber, "trackingNumber cannot be null");
        Objects.requireNonNull(newStatus, "newStatus cannot be null");
        Objects.requireNonNull(occurredAt, "occurredAt cannot be null");
    }
}
