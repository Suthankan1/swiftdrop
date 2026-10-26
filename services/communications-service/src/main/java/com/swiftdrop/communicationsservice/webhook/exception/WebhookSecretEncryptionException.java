package com.swiftdrop.communicationsservice.webhook.exception;

/**
 * Thrown when encryption or decryption of webhook signing material fails.
 */
public class WebhookSecretEncryptionException extends RuntimeException {
    public WebhookSecretEncryptionException(String message) {
        super(message);
    }

    public WebhookSecretEncryptionException(String message, Throwable cause) {
        super(message, cause);
    }
}
