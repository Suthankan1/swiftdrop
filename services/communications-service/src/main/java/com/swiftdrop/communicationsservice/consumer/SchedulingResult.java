package com.swiftdrop.communicationsservice.consumer;

import com.swiftdrop.communicationsservice.transaction.InboxAndNotificationResult;

import java.util.UUID;

/**
 * Result outcome of the notification scheduling pipeline.
 */
public record SchedulingResult(
        UUID eventId,
        boolean isDuplicate,
        boolean newlyPersisted
) {
    public static SchedulingResult duplicate(UUID eventId) {
        return new SchedulingResult(eventId, true, false);
    }

    public static SchedulingResult persisted(InboxAndNotificationResult result) {
        return new SchedulingResult(result.consumedEvent().getEventId(), false, true);
    }
}
