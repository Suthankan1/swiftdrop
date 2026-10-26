package com.swiftdrop.graphql.resolver;

/**
 * Authoritative Courier parcel assignment item matching GraphQL SDL CourierParcelItem type.
 */
public record CourierParcelItem(
        String parcelId,
        String trackingNumber,
        String currentStatus,
        String pickupAddress,
        String dropoffAddress,
        String recipientName,
        String recipientPhone,
        String assignedAt,
        int version
) {}
