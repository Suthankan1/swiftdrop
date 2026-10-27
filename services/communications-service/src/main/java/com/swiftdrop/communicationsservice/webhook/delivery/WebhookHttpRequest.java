package com.swiftdrop.communicationsservice.webhook.delivery;

import java.time.Duration;

/**
 * Value object representing an outbound signed webhook HTTP dispatch request.
 */
public record WebhookHttpRequest(
        String url,
        String signatureHeader,
        String correlationId,
        String payloadJson,
        Duration timeout
) {
    public WebhookHttpRequest {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("Destination URL must not be null or blank");
        }
        if (signatureHeader == null || signatureHeader.isBlank()) {
            throw new IllegalArgumentException("Signature header must not be null or blank");
        }
        if (payloadJson == null) {
            throw new IllegalArgumentException("Payload JSON must not be null");
        }
    }
}
