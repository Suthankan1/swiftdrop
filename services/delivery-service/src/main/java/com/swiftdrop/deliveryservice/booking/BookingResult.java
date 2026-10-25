package com.swiftdrop.deliveryservice.booking;

import com.swiftdrop.deliveryservice.core.ParcelStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Result contract returned upon successful parcel booking.
 */
public record BookingResult(
        UUID parcelId,
        String trackingNumber,
        ParcelStatus status,
        Long version,
        Instant createdAt
) {
}
