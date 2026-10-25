package com.swiftdrop.deliveryservice.status;

/**
 * Thrown when transition parameters are invalid or missing (e.g. missing failure reason or missing delivery proofId).
 * Maps to HTTP 400 Bad Request.
 */
public class InvalidTransitionParametersException extends InvalidTransitionException {

    public InvalidTransitionParametersException(String message) {
        super(message);
    }
}
