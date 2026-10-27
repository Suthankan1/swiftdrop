package com.swiftdrop.communicationsservice.consumer;

/**
 * Thrown when an authorized recipient is not permitted or eligible for the requested notification channel.
 */
public class NoEligibleNotificationTargetException extends RuntimeException {

    public NoEligibleNotificationTargetException(String message) {
        super(message);
    }
}
