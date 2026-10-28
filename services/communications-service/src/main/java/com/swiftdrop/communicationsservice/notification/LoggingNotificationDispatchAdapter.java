package com.swiftdrop.communicationsservice.notification;

import com.swiftdrop.communicationsservice.notification.delivery.NotificationAttemptResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Bounded logging notification adapter for the EMAIL channel.
 * Retains jobs in PENDING state in the database for worker polling.
 * Strictly omits any personal contact data from log statements.
 */
@Component
public class LoggingNotificationDispatchAdapter implements NotificationDispatchAdapter {

    private static final Logger log = LoggerFactory.getLogger(LoggingNotificationDispatchAdapter.class);

    @Override
    public NotificationChannel getChannel() {
        return NotificationChannel.EMAIL;
    }

    @Override
    public void dispatch(NotificationJob job) {
        if (job == null) {
            return;
        }
        log.info("Notification job queued [notificationId={}, eventId={}, channel={}, template={}, status={}]",
                job.getNotificationId(), job.getEventId(), job.getChannel(), job.getTemplate(), job.getStatus());
    }

    @Override
    public NotificationAttemptResult dispatchAttempt(NotificationJob job) {
        dispatch(job);
        return NotificationAttemptResult.transientFailure("LOGGING_ONLY", "LOGGING_ADAPTER_PENDING", 0);
    }
}
