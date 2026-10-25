package com.swiftdrop.deliveryservice.status;

/**
 * Thrown when an actor is not authorized to transition parcel status (e.g. missing role or unassigned courier).
 * Maps to HTTP 403 Forbidden.
 */
public class StatusAccessDeniedException extends RuntimeException {

    public StatusAccessDeniedException(String message) {
        super(message);
    }
}
