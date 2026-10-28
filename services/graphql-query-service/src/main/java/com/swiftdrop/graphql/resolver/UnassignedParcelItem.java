package com.swiftdrop.graphql.resolver;

import java.util.Objects;

/**
 * GraphQL projection item for an unassigned parcel in the dispatcher queue.
 * Conforms to SDL type UnassignedParcelItem.
 */
public record UnassignedParcelItem(
        String parcelId,
        String trackingNumber,
        String pickupZone,
        String dropoffZone,
        double weightKg,
        String sizeCategory,
        String createdAt,
        int version
) {
    public UnassignedParcelItem {
        Objects.requireNonNull(parcelId, "parcelId cannot be null");
        Objects.requireNonNull(trackingNumber, "trackingNumber cannot be null");
        Objects.requireNonNull(pickupZone, "pickupZone cannot be null");
        Objects.requireNonNull(dropoffZone, "dropoffZone cannot be null");
        Objects.requireNonNull(sizeCategory, "sizeCategory cannot be null");
        Objects.requireNonNull(createdAt, "createdAt cannot be null");
        if (!Double.isFinite(weightKg) || weightKg <= 0.0) {
            throw new IllegalArgumentException("weightKg must be finite and > 0: " + weightKg);
        }
        if (version < 0) {
            throw new IllegalArgumentException("version must be non-negative: " + version);
        }
    }
}
