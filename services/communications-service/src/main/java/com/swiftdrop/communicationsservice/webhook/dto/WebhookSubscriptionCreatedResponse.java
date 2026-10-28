package com.swiftdrop.communicationsservice.webhook.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Creation response returned ONCE upon successful webhook registration (HTTP 201).
 * Contains the plaintext hmacSigningSecret, which must never be exposed subsequently.
 */
public record WebhookSubscriptionCreatedResponse(
        UUID subscriptionId,
        UUID merchantId,
        String httpsUrl,
        List<String> allowedEventTypes,
        String hmacSigningSecret,
        boolean active,
        Instant createdAt
) {
}
