package com.swiftdrop.communicationsservice.transaction;

import com.swiftdrop.communicationsservice.inbox.ConsumedEvent;
import com.swiftdrop.communicationsservice.notification.NotificationJob;

public record InboxAndNotificationResult(
    ConsumedEvent consumedEvent,
    NotificationJob notificationJob,
    boolean newlyPersisted
) {}
