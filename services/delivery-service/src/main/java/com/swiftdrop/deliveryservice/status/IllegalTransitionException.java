package com.swiftdrop.deliveryservice.status;

/**
 * Thrown when an illegal lifecycle transition is attempted (e.g. backward transition or skipping stages).
 * Maps to HTTP 422 Unprocessable Content.
 */
public class IllegalTransitionException extends InvalidTransitionException {

    public IllegalTransitionException(String message) {
        super(message);
    }
}
