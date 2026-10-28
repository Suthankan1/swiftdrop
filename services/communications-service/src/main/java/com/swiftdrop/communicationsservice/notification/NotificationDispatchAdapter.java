package com.swiftdrop.communicationsservice.notification;

import com.swiftdrop.communicationsservice.notification.delivery.NotificationAttemptResult;

/**
 * Adapter boundary for outbound notification delivery (Issue #32 / Issue #117).
 */
public interface NotificationDispatchAdapter {

    /**
     * The notification channel supported by this adapter.
     */
    NotificationChannel getChannel();

    /**
     * Dispatches a notification job and returns a structured attempt outcome.
     *
     * @param job the durable notification job
     * @return structured attempt outcome
     */
    NotificationAttemptResult dispatchAttempt(NotificationJob job);

    /**
     * Legacy dispatch method for backward compatibility.
     *
     * @param job the durable notification job
     */
    default void dispatch(NotificationJob job) {
        dispatchAttempt(job);
    }
}
