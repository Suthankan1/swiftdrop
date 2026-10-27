package com.swiftdrop.communicationsservice.webhook.delivery;

/**
 * High-level classified outcome categories for outbound webhook delivery attempts.
 */
public enum WebhookOutcomeCategory {
    SUCCESS,
    REDIRECT,
    CLIENT_ERROR,
    RATE_LIMITED,
    SERVER_ERROR,
    TIMEOUT,
    NETWORK_FAILURE,
    SECURITY_FAILURE,
    UNEXPECTED_FAILURE
}
