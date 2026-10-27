package com.swiftdrop.communicationsservice.webhook.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request payload for replaying a terminally failed webhook delivery job.
 * Enforces mandatory operator reason recording for durable audit trails.
 */
public record ReplayWebhookDeliveryRequest(
        @NotBlank(message = "Replay reason is required.")
        @Size(min = 3, max = 500, message = "Replay reason must be between 3 and 500 characters.")
        String reason
) {
}
