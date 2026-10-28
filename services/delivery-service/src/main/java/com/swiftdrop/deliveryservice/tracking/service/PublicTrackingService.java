package com.swiftdrop.deliveryservice.tracking.service;

import com.swiftdrop.deliveryservice.tracking.dto.PublicTrackingResponse;

/**
 * Service contract for privacy-preserving, read-only parcel tracking queries.
 */
public interface PublicTrackingService {

    /**
     * Resolves a public tracking projection by tracking reference.
     *
     * @param trackingNumber public tracking reference (e.g., SWD-20261023-A1B2C3)
     * @return PublicTrackingResponse containing only allowlisted public fields
     */
    PublicTrackingResponse getPublicTracking(String trackingNumber);
}
