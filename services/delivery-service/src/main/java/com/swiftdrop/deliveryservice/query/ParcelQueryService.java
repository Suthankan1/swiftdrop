package com.swiftdrop.deliveryservice.query;

import java.util.Optional;
import java.util.UUID;

/**
 * Service contract for resolving parcel projections for internal gRPC resolvers
 * and GraphQL composite dashboards.
 */
public interface ParcelQueryService {

    /**
     * Finds an internal parcel projection by its UUID.
     */
    Optional<ParcelProjection> findParcelById(UUID parcelId);

    /**
     * Finds an internal parcel projection by its tracking number.
     */
    Optional<ParcelProjection> findParcelByTrackingNumber(String trackingNumber);
}
