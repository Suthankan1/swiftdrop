package com.swiftdrop.communicationsservice.webhook.dto;

import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Proposed payload for subscription mutation extension (Refs #116 / DEC-COM-01).
 * Immutable fields (subscriptionId, merchantId, secretRef, createdAt) cannot be modified.
 */
public record UpdateWebhookSubscriptionRequest(
        @Size(max = 1000, message = "httpsUrl must not exceed 1000 characters")
        String httpsUrl,

        List<String> allowedEventTypes
) {
}
