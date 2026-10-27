package com.swiftdrop.communicationsservice.consumer;

/**
 * Thrown when an incoming Kafka record fails schema, version, envelope, or key validation.
 */
public class EventValidationException extends RuntimeException {

    public EventValidationException(String message) {
        super(message);
    }

    public EventValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
