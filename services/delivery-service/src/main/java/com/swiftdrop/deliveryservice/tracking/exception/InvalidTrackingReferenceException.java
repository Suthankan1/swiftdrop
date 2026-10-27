package com.swiftdrop.deliveryservice.tracking.exception;

/**
 * Thrown when a supplied tracking number violates length, character, or format rules.
 */
public class InvalidTrackingReferenceException extends RuntimeException {

    public InvalidTrackingReferenceException(String message) {
        super(message);
    }
}
