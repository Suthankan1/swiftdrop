package com.swiftdrop.communicationsservice.webhook.exception;

/**
 * Thrown when a webhook replay is requested on a delivery that is not in a replayable terminal state
 * (e.g., job is still PENDING, PROCESSING, or has already completed with SUCCESS).
 */
public class InvalidWebhookReplayException extends RuntimeException {
    public InvalidWebhookReplayException(String message) {
        super(message);
    }
}
