package com.swiftdrop.communicationsservice.webhook.exception;

/**
 * Thrown when a requested webhook subscription does not exist.
 */
public class WebhookSubscriptionNotFoundException extends RuntimeException {
    public WebhookSubscriptionNotFoundException(String message) {
        super(message);
    }
}
