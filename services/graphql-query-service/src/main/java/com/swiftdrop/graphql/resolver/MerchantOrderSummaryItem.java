package com.swiftdrop.graphql.resolver;

/**
 * Authoritative merchant order summary item matching GraphQL SDL MerchantOrderSummary type.
 * Named MerchantOrderSummaryItem to avoid collision with the generated Protobuf
 * com.swiftdrop.delivery.v1.MerchantOrderSummary class.
 */
public record MerchantOrderSummaryItem(
        String parcelId,
        String trackingNumber,
        String currentStatus,
        double weightKg,
        int feeMinorUnits,
        String currency,
        String createdAt,
        String lastStatusUpdate
) {}
