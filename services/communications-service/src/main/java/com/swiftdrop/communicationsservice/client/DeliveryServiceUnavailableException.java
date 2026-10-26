package com.swiftdrop.communicationsservice.client;

/**
 * Retryable exception thrown when Delivery service is unavailable, unreachable, or exceeded deadline.
 * Communications worker can safely retry Kafka job when encountering this exception.
 */
public class DeliveryServiceUnavailableException extends DeliveryAuthorizationException {
    public DeliveryServiceUnavailableException(String message) {
        super(message);
    }

    public DeliveryServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
