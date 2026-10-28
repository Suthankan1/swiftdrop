package com.swiftdrop.deliveryservice.tracking.dto;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Strictly redacted public tracking Server-Sent Event (SSE) payload.
 * Emitted over GET /api/v1/tracking/{trackingNumber}/events for unauthenticated viewers.
 *
 * Guarantees zero internal identifiers (no parcelId, no quoteId, no merchantId),
 * zero customer personal data (no names, no phone numbers, no street addresses),
 * zero credentials or tokens, and zero private delivery proof keys or failure notes.
 */
public record PublicTrackingSseEvent(
        UUID eventId,
        String trackingNumber,
        long aggregateVersion,
        String oldStatus,
        String newStatus,
        String cityOrZone,
        Instant occurredAt
) {
    public PublicTrackingSseEvent {
        Objects.requireNonNull(eventId, "eventId cannot be null");
        Objects.requireNonNull(trackingNumber, "trackingNumber cannot be null");
        Objects.requireNonNull(newStatus, "newStatus cannot be null");
        Objects.requireNonNull(occurredAt, "occurredAt cannot be null");
    }
}
