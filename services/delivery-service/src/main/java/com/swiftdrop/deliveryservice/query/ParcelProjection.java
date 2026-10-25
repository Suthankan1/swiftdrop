package com.swiftdrop.deliveryservice.query;

import com.swiftdrop.deliveryservice.core.ParcelStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Authoritative read projection of a parcel aggregate for cross-service queries
 * and GraphQL dashboard aggregations (matching DeliveryInternalService.GetParcelInternal).
 */
public record ParcelProjection(
        UUID parcelId,
        String trackingNumber,
        ParcelStatus currentStatus,
        Long version,
        Long amountMinorUnits,
        String currency,
        String createdBySub,
        UUID merchantId,
        String activeCourierSub,
        Instant createdAt
) {
}
