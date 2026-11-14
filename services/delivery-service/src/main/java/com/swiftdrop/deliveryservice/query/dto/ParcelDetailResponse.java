package com.swiftdrop.deliveryservice.query.dto;

import com.swiftdrop.deliveryservice.core.ParcelStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Authoritative response payload for GET /api/v1/parcels/{parcelId}.
 * Matches OpenAPI schema ParcelDetailResponse in openapi-delivery.yaml.
 */
public record ParcelDetailResponse(
        UUID parcelId,
        String trackingNumber,
        String createdBySub,
        UUID merchantId,
        ParcelStatus currentStatus,
        Long version,
        Double weightKg,
        String dimensions,
        Long amountMinorUnits,
        String currency,
        String rateVersion,
        String pickupAddress,
        String dropoffAddress,
        String activeCourierSub,
        Instant createdAt,
        Instant updatedAt
) {
    /** Compatibility for command snapshots and older internal producers without update metadata. */
    public ParcelDetailResponse(UUID parcelId, String trackingNumber, String createdBySub, UUID merchantId,
            ParcelStatus currentStatus, Long version, Double weightKg, String dimensions,
            Long amountMinorUnits, String currency, String rateVersion, String pickupAddress,
            String dropoffAddress, String activeCourierSub, Instant createdAt) {
        this(parcelId, trackingNumber, createdBySub, merchantId, currentStatus, version, weightKg,
                dimensions, amountMinorUnits, currency, rateVersion, pickupAddress, dropoffAddress,
                activeCourierSub, createdAt, null);
    }
}
