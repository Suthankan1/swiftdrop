package com.swiftdrop.deliveryservice.status.dto;

import com.swiftdrop.deliveryservice.core.ParcelStatus;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Request payload for transitioning parcel lifecycle status.
 *
 * Implements REST contract schema UpdateStatusRequest in openapi-delivery.yaml.
 */
public record UpdateStatusRequest(
        @NotNull(message = "newStatus is required")
        ParcelStatus newStatus,

        @NotNull(message = "expectedVersion is required")
        Long expectedVersion,

        String note,

        String reason,

        UUID proofId
) {
}
