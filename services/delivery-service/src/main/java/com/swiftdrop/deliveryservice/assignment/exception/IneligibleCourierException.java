package com.swiftdrop.deliveryservice.assignment.exception;

/**
 * Thrown when target courier fails Accounts Service eligibility verification.
 * Maps to HTTP 400 Bad Request.
 */
public class IneligibleCourierException extends RuntimeException {

    public IneligibleCourierException(String message) {
        super(message);
    }
}
