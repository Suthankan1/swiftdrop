package com.swiftdrop.communicationsservice.webhook.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Request payload for POST /api/v1/merchants/webhooks mirroring openapi-communications.yaml.
 */
public record CreateWebhookSubscriptionRequest(
        @NotBlank(message = "httpsUrl is required")
        @Size(max = 1000, message = "httpsUrl must not exceed 1000 characters")
        String httpsUrl,

        @NotEmpty(message = "allowedEventTypes must not be empty")
        List<String> allowedEventTypes
) {
}
