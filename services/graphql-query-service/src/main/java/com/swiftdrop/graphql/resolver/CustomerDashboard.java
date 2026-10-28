package com.swiftdrop.graphql.resolver;

import java.util.List;

/**
 * Authoritative Customer dashboard record matching GraphQL SDL CustomerDashboard type.
 * userId is sourced strictly from the verified authenticated JWT subject.
 */
public record CustomerDashboard(
        String userId,
        int activeDeliveriesCount,
        int completedDeliveriesCount,
        List<CustomerParcelSummaryItem> recentParcels
) {
    public CustomerDashboard {
        recentParcels = recentParcels == null ? List.of() : List.copyOf(recentParcels);
    }
}
