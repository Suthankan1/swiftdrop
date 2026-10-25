package com.swiftdrop.deliveryservice.assignment;

import java.util.Optional;
import java.util.UUID;

/**
 * Bounded feature contract for Courier Assignment in Delivery Operations.
 *
 * Owned and implemented under Issue #24 (Single-Active-Courier Assignment Command).
 */
public interface CourierAssignmentContract {

    /**
     * Checks if the given courier holds an active assignment for the parcel.
     */
    boolean hasActiveAssignment(UUID parcelId, String courierSub);

    /**
     * Retrieves the active courier subject assigned to the parcel, if any.
     */
    Optional<String> getActiveCourierSub(UUID parcelId);

    /**
     * Retrieves the active assignment workload count for a specific courier.
     */
    int getActiveCourierWorkload(String courierSub);
}
