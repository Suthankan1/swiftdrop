package com.swiftdrop.deliveryservice.tracking.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.swiftdrop.deliveryservice.core.ParcelStatus;

import java.time.Instant;

/**
 * Public-safe timeline entry. Exposes coarse status checkpoint and timestamp.
 * Strictly redacts private courier notes, failure reasons, and exact coordinates.
 * Conforms to TrackingTimelineItem in openapi-delivery.yaml.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TrackingTimelineItem(
        ParcelStatus status,
        Instant occurredAt,
        String cityOrZone
) {
}
