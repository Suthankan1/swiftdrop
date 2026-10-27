package com.swiftdrop.communicationsservice.webhook.delivery;

/**
 * Value object representing a computed HMAC-SHA256 signature for outbound webhook dispatches.
 * Format: X-SwiftDrop-Signature: t={timestamp},v1={hexDigest}
 * Signed data: t + "." + payload_json
 */
public record WebhookSignature(
        long timestamp,
        String hexDigest,
        String headerValue
) {
    public WebhookSignature {
        if (hexDigest == null || hexDigest.isBlank()) {
            throw new IllegalArgumentException("Signature hex digest must not be null or blank");
        }
        if (headerValue == null || headerValue.isBlank()) {
            throw new IllegalArgumentException("Signature header value must not be null or blank");
        }
    }
}
