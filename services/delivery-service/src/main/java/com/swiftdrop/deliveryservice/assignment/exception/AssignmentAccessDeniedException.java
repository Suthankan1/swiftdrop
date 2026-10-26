package com.swiftdrop.deliveryservice.assignment.exception;

/**
 * Thrown when caller lacks DISPATCHER or ADMIN authority to assign couriers.
 * Maps to HTTP 403 Forbidden.
 */
public class AssignmentAccessDeniedException extends RuntimeException {

    public AssignmentAccessDeniedException(String message) {
        super(message);
    }
}
