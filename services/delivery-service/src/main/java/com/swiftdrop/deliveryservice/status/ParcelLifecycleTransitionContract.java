package com.swiftdrop.deliveryservice.status;

import com.swiftdrop.deliveryservice.core.ParcelStatus;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Authoritative Delivery Lifecycle State Machine Contract.
 *
 * Defines the state transition rules, terminal state constraints, and validation
 * guards per SwiftDrop Architecture Baseline v1.3 and Issue #23.
 */
public interface ParcelLifecycleTransitionContract {

    // Approved terminal failure reason codes
    String REASON_RECIPIENT_UNAVAILABLE = "RECIPIENT_UNAVAILABLE";
    String REASON_INCORRECT_ADDRESS = "INCORRECT_ADDRESS";
    String REASON_REFUSED_BY_RECIPIENT = "REFUSED_BY_RECIPIENT";
    String REASON_PARCEL_DAMAGED = "PARCEL_DAMAGED";
    String REASON_FORCE_MAJEURE = "FORCE_MAJEURE";
    String REASON_CANCELLED_BY_SENDER = "CANCELLED_BY_SENDER";
    String REASON_PICKUP_FAILED_SENDER_ABSENT = "PICKUP_FAILED_SENDER_ABSENT";

    Set<String> APPROVED_FAILURE_REASONS = Set.of(
            REASON_RECIPIENT_UNAVAILABLE,
            REASON_INCORRECT_ADDRESS,
            REASON_REFUSED_BY_RECIPIENT,
            REASON_PARCEL_DAMAGED,
            REASON_FORCE_MAJEURE,
            REASON_CANCELLED_BY_SENDER,
            REASON_PICKUP_FAILED_SENDER_ABSENT
    );

    Map<ParcelStatus, Set<ParcelStatus>> PERMITTED_TRANSITIONS = Map.of(
            ParcelStatus.CREATED, Set.of(ParcelStatus.PICKED_UP, ParcelStatus.FAILED),
            ParcelStatus.PICKED_UP, Set.of(ParcelStatus.IN_TRANSIT, ParcelStatus.FAILED),
            ParcelStatus.IN_TRANSIT, Set.of(ParcelStatus.OUT_FOR_DELIVERY, ParcelStatus.FAILED),
            ParcelStatus.OUT_FOR_DELIVERY, Set.of(ParcelStatus.DELIVERED, ParcelStatus.FAILED),
            ParcelStatus.DELIVERED, Collections.emptySet(),
            ParcelStatus.FAILED, Collections.emptySet()
    );

    /**
     * Checks if a transition from currentStatus to targetStatus is permitted.
     */
    static boolean isTransitionPermitted(ParcelStatus currentStatus, ParcelStatus targetStatus) {
        if (currentStatus == null || targetStatus == null) {
            return false;
        }
        Set<ParcelStatus> allowed = PERMITTED_TRANSITIONS.getOrDefault(currentStatus, Collections.emptySet());
        return allowed.contains(targetStatus);
    }

    /**
     * Validates a proposed state transition, throwing InvalidTransitionException if forbidden.
     */
    static void validateTransition(ParcelStatus currentStatus,
                                   ParcelStatus targetStatus,
                                   String failureReason,
                                   UUID proofId) {
        if (currentStatus == null) {
            throw new IllegalArgumentException("Current status cannot be null");
        }
        if (targetStatus == null) {
            throw new IllegalArgumentException("Target status cannot be null");
        }
        if (currentStatus.isTerminal()) {
            throw new InvalidTransitionException(
                    "Cannot transition from terminal state " + currentStatus + " to " + targetStatus
            );
        }
        if (!isTransitionPermitted(currentStatus, targetStatus)) {
            throw new InvalidTransitionException(
                    "Illegal transition from " + currentStatus + " to " + targetStatus
            );
        }
        if (targetStatus == ParcelStatus.FAILED) {
            if (failureReason == null || failureReason.isBlank()) {
                throw new InvalidTransitionException("Failure reason is mandatory when transitioning to FAILED");
            }
            if (!APPROVED_FAILURE_REASONS.contains(failureReason)) {
                throw new InvalidTransitionException("Unapproved failure reason: " + failureReason);
            }
        }
        if (targetStatus == ParcelStatus.DELIVERED && proofId == null) {
            throw new InvalidTransitionException("Proof of delivery (proofId) is mandatory when transitioning to DELIVERED");
        }
    }
}
