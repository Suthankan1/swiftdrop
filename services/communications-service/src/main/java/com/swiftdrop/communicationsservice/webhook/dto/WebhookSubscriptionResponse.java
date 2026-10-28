package com.swiftdrop.communicationsservice.webhook.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Standard webhook subscription response mirroring openapi-communications.yaml (v1.0.0).
 * Plaintext signing secrets are strictly omitted.
 */
public record WebhookSubscriptionResponse(
        UUID subscriptionId,
        UUID merchantId,
        String httpsUrl,
        List<String> allowedEventTypes,
        boolean active,
        Instant createdAt
) {
}
