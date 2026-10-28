package com.swiftdrop.communicationsservice.notification.dto;

import java.time.Instant;
import java.util.UUID;

public record NotificationAttemptResponse(
        UUID attemptId,
        UUID notificationId,
        int attemptNumber,
        String channel,
        String outcome,
        String failureCategory,
        String safeErrorCode,
        Integer durationMs,
        Instant attemptedAt
) {}
