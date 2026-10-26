package com.swiftdrop.graphql.resolver;

import java.util.List;

/**
 * Authoritative Courier operational dashboard record matching GraphQL SDL CourierDashboard type.
 */
public record CourierDashboard(
        String courierSub,
        int activeAssignedCount,
        int todayDeliveredCount,
        int todayFailedCount,
        List<CourierParcelItem> assignedParcels
) {
    public CourierDashboard {
        assignedParcels = assignedParcels == null ? List.of() : List.copyOf(assignedParcels);
    }
}
