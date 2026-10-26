package com.swiftdrop.deliveryservice.assignment.exception;

/**
 * Thrown when an assignment concurrency conflict occurs (parcel version mismatch or already assigned).
 * Maps to HTTP 409 Conflict.
 */
public class AssignmentConflictException extends RuntimeException {

    public AssignmentConflictException(String message) {
        super(message);
    }
}
