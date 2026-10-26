package com.swiftdrop.communicationsservice.client;

/**
 * Base runtime exception for Delivery recipient authorization failures.
 */
public class DeliveryAuthorizationException extends RuntimeException {
    public DeliveryAuthorizationException(String message) {
        super(message);
    }

    public DeliveryAuthorizationException(String message, Throwable cause) {
        super(message, cause);
    }
}
