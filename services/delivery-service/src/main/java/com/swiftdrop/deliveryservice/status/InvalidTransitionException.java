package com.swiftdrop.deliveryservice.status;

/**
 * Thrown when an illegal or unauthorized status transition is attempted on a parcel.
 */
public class InvalidTransitionException extends RuntimeException {

    public InvalidTransitionException(String message) {
        super(message);
    }
}
