package com.swiftdrop.graphql.resolver;

import java.util.List;
import java.util.Objects;

/**
 * GraphQL aggregate dashboard response for Query.dispatcherDashboard.
 * Conforms to SDL type DispatcherDashboard.
 */
public record DispatcherDashboard(
        List<UnassignedParcelItem> unassignedParcels,
        PageInfo pageInfo,
        List<CourierWorkloadSummary> availableCouriers
) {
    public DispatcherDashboard {
        Objects.requireNonNull(pageInfo, "pageInfo cannot be null");
        unassignedParcels = unassignedParcels != null ? List.copyOf(unassignedParcels) : List.of();
        availableCouriers = availableCouriers != null ? List.copyOf(availableCouriers) : List.of();
    }
}
