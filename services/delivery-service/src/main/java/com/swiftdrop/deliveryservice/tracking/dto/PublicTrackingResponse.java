package com.swiftdrop.deliveryservice.tracking.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.swiftdrop.deliveryservice.core.ParcelStatus;

import java.util.List;

/**
 * Public, privacy-preserving tracking view for senders and recipients.
 * Conforms to OpenAPI PublicTrackingResponse in openapi-delivery.yaml.
 *
 * Strictly allowlisted: omits all customer/recipient names, phone numbers,
 * exact addresses, driver/courier identities, proof links, and internal database identifiers.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PublicTrackingResponse(
        String trackingNumber,
        ParcelStatus currentStatus,
        String originCity,
        String destinationCity,
        List<TrackingTimelineItem> timeline
) {
    public PublicTrackingResponse {
        timeline = timeline != null ? List.copyOf(timeline) : List.of();
    }
}
