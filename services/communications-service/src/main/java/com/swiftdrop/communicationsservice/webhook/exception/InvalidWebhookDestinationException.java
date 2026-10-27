package com.swiftdrop.communicationsservice.webhook.exception;

/**
 * Thrown when a webhook destination URL violates transport security (HTTPS) or SSRF safety rules.
 */
public class InvalidWebhookDestinationException extends RuntimeException {
    public InvalidWebhookDestinationException(String message) {
        super(message);
    }
}
