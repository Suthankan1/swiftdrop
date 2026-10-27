package com.swiftdrop.communicationsservice.webhook.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Single delivery outcome item mirroring WebhookDeliveryItem in openapi-communications.yaml.
 */
public record WebhookDeliveryItem(
        UUID deliveryId,
        UUID eventId,
        UUID parcelId,
        String status,
        int attemptCount,
        Instant lastAttemptAt,
        Integer lastHttpStatusCode,
        Integer lastResponseTimeMs,
        String errorSummary
) {
}
