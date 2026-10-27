package com.swiftdrop.communicationsservice.consumer.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Domain event envelope matching contracts/events/parcel.status.changed.v1.schema.json.
 */
public record ParcelStatusChangedEvent(
        UUID eventId,
        String eventType,
        String schemaVersion,
        UUID aggregateId,
        String aggregateType,
        Long aggregateVersion,
        Instant occurredAt,
        String correlationId,
        String producer,
        ParcelStatusChangedPayload payload
) {
}
