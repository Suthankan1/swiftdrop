package com.swiftdrop.graphql.resolver;

import java.util.Objects;

/**
 * GraphQL projection item for available courier operational workload summary.
 * Conforms to SDL type CourierWorkloadSummary.
 */
public record CourierWorkloadSummary(
        String courierSub,
        String fullName,
        int activeAssignmentsCount,
        int maxCapacity,
        boolean eligible
) {
    public CourierWorkloadSummary {
        Objects.requireNonNull(courierSub, "courierSub cannot be null");
        Objects.requireNonNull(fullName, "fullName cannot be null");
        if (activeAssignmentsCount < 0) {
            throw new IllegalArgumentException("activeAssignmentsCount must be non-negative: " + activeAssignmentsCount);
        }
        if (maxCapacity < 1) {
            throw new IllegalArgumentException("maxCapacity must be >= 1: " + maxCapacity);
        }
    }
}
