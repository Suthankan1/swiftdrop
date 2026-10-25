package com.swiftdrop.deliveryservice.core;

import java.util.Collections;
import java.util.Set;

/**
 * Authoritative parcel lifecycle status enum for SwiftDrop Delivery Operations.
 *
 * Lifecycle flow:
 *   CREATED -> PICKED_UP -> IN_TRANSIT -> OUT_FOR_DELIVERY -> DELIVERED (Terminal)
 *         \            \            \                 \
 *          +------------+------------+-----------------+-> FAILED (Terminal)
 */
public enum ParcelStatus {
    CREATED,
    PICKED_UP,
    IN_TRANSIT,
    OUT_FOR_DELIVERY,
    DELIVERED,
    FAILED;

    private static final Set<ParcelStatus> TERMINAL_STATES = Set.of(DELIVERED, FAILED);

    /**
     * Checks if this status is terminal (immutable).
     */
    public boolean isTerminal() {
        return TERMINAL_STATES.contains(this);
    }

    /**
     * Returns the set of valid next forward statuses from this status.
     */
    public Set<ParcelStatus> nextValidStatuses() {
        return switch (this) {
            case CREATED -> Set.of(PICKED_UP, FAILED);
            case PICKED_UP -> Set.of(IN_TRANSIT, FAILED);
            case IN_TRANSIT -> Set.of(OUT_FOR_DELIVERY, FAILED);
            case OUT_FOR_DELIVERY -> Set.of(DELIVERED, FAILED);
            case DELIVERED, FAILED -> Collections.emptySet();
        };
    }
}
