package com.swiftdrop.communicationsservice.webhook.exception;

/**
 * Thrown when a requested webhook delivery record does not exist.
 */
public class WebhookDeliveryNotFoundException extends RuntimeException {
    public WebhookDeliveryNotFoundException(String message) {
        super(message);
    }
}
