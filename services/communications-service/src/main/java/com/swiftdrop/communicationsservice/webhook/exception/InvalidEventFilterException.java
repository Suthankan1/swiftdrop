package com.swiftdrop.communicationsservice.webhook.exception;

/**
 * Thrown when an allowedEventTypes array contains empty, null, or unsupported domain event types.
 */
public class InvalidEventFilterException extends RuntimeException {
    public InvalidEventFilterException(String message) {
        super(message);
    }
}
