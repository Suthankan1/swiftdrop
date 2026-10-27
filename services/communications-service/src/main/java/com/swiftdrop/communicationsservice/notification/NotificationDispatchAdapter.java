package com.swiftdrop.communicationsservice.notification;

/**
 * Minimal bounded adapter boundary for outbound notification delivery (Issue #32).
 * Actual delivery workers, external email/SMS transport, and retry schedules belong to Issue #117.
 */
public interface NotificationDispatchAdapter {

    /**
     * The notification channel supported by this adapter.
     */
    NotificationChannel getChannel();

    /**
     * Dispatches or queues a persisted notification job.
     *
     * @param job the durable notification job (status PENDING)
     */
    void dispatch(NotificationJob job);
}
