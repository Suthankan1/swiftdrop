package com.swiftdrop.communicationsservice.notification.dto;

import java.time.Instant;
import java.util.UUID;

public record PoisonEventQuarantineResponse(
        UUID quarantineId,
        String consumerGroup,
        String sourceTopic,
        int sourcePartition,
        long sourceOffset,
        UUID eventIdIfValid,
        String eventTypeIfValid,
        String failureCategory,
        String safeErrorSummary,
        String payloadDigest,
        String status,
        Instant quarantinedAt,
        Instant replayedAt,
        String replayReason
) {}
