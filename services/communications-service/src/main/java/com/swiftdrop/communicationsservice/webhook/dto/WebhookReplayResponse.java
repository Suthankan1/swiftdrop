package com.swiftdrop.communicationsservice.webhook.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Response payload confirming successful authorization and scheduling of a webhook delivery replay.
 */
public record WebhookReplayResponse(
        UUID replayId,
        UUID deliveryId,
        UUID subscriptionId,
        String previousStatus,
        String newStatus,
        String reason,
        Instant replayedAt,
        Instant nextAttemptAt
) {
}
