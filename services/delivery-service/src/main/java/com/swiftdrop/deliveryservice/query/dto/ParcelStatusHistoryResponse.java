package com.swiftdrop.deliveryservice.query.dto;

import com.swiftdrop.deliveryservice.core.ParcelStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Historical status transition audit entry for GET /api/v1/parcels/{parcelId}/history.
 * Represents an immutable chronological event from delivery_db.status_history.
 */
public record ParcelStatusHistoryResponse(
        UUID historyId,
        UUID parcelId,
        ParcelStatus previousStatus,
        ParcelStatus newStatus,
        String reason,
        String note,
        UUID proofId,
        Long resultingVersion,
        Instant occurredAt
) {
}
