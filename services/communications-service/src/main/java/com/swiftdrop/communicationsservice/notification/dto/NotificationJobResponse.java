package com.swiftdrop.communicationsservice.notification.dto;

import java.time.Instant;
import java.util.UUID;

public record NotificationJobResponse(
        UUID notificationId,
        UUID eventId,
        UUID parcelId,
        String channel,
        String status,
        int attempts,
        Instant nextAttemptAt,
        Instant createdAt,
        Instant updatedAt,
        String lastFailureCategory
) {}
