package com.swiftdrop.graphql.resolver;

/**
 * Authoritative customer parcel summary item matching GraphQL SDL CustomerParcelSummary type.
 * Named CustomerParcelSummaryItem to avoid collision with the generated Protobuf
 * com.swiftdrop.delivery.v1.CustomerParcelSummary class.
 */
public record CustomerParcelSummaryItem(
        String parcelId,
        String trackingNumber,
        String currentStatus,
        int amountMinorUnits,
        String currency,
        String recipientCity,
        String createdAt,
        String updatedAt
) {}
