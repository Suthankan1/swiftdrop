package com.swiftdrop.deliveryservice.status;

/**
 * Thrown when attempting to transition out of an immutable terminal state (DELIVERED or FAILED).
 * Maps to HTTP 409 Conflict.
 */
public class TerminalStateMutationException extends InvalidTransitionException {

    public TerminalStateMutationException(String message) {
        super(message);
    }
}
