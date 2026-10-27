package com.swiftdrop.communicationsservice.webhook.exception;

/**
 * Thrown when persisting a webhook delivery attempt or updating job state fails.
 */
public class WebhookAttemptPersistenceException extends RuntimeException {

    public WebhookAttemptPersistenceException(String message) {
        super(message);
    }

    public WebhookAttemptPersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
