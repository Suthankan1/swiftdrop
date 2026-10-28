package com.swiftdrop.deliveryservice.assignment;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Bounded feature contract for Courier Assignment in Delivery Operations.
 *
 * Owned and implemented under Issue #24 and Issue #125.
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

    /**
     * Closes the active courier assignment when a parcel transitions to a terminal state (DELIVERED or FAILED).
     *
     * @param parcelId ID of the terminal parcel
     * @param endedAt timestamp when terminal status occurred
     */
    default void closeActiveAssignmentForTerminalParcel(UUID parcelId, Instant endedAt) {
    }
}
