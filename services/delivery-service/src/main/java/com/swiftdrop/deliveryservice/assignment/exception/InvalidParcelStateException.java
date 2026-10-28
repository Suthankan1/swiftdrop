package com.swiftdrop.deliveryservice.assignment.exception;

/**
 * Thrown when attempting to assign a parcel that is not in CREATED state.
 * Maps to HTTP 400 Bad Request.
 */
public class InvalidParcelStateException extends RuntimeException {

    public InvalidParcelStateException(String message) {
        super(message);
    }
}
