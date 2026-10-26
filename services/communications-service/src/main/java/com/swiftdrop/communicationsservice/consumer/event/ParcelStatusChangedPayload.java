package com.swiftdrop.communicationsservice.consumer.event;

import java.util.UUID;

/**
 * Domain payload matching contracts/events/parcel.status.changed.v1.schema.json.
 * Strictly carries opaque identifiers and zero sensitive street-level PII or recipient contact data.
 */
public record ParcelStatusChangedPayload(
        UUID parcelId,
        String trackingNumber,
        String oldStatus,
        String newStatus,
        String reason,
        UUID merchantId,
        String courierSub,
        String cityOrZone
) {
}
