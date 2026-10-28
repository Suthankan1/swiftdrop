package com.swiftdrop.communicationsservice.notification;

import com.swiftdrop.communicationsservice.notification.delivery.EmailNotificationTransport;
import com.swiftdrop.communicationsservice.notification.delivery.NotificationAttemptResult;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Primary dispatch adapter for EMAIL notifications, delegating to the verified SMTP transport (Issue #117).
 */
@Component
@Primary
public class EmailNotificationDispatchAdapter implements NotificationDispatchAdapter {

    private final EmailNotificationTransport emailTransport;

    public EmailNotificationDispatchAdapter(EmailNotificationTransport emailTransport) {
        this.emailTransport = Objects.requireNonNull(emailTransport, "emailTransport cannot be null");
    }

    @Override
    public NotificationChannel getChannel() {
        return NotificationChannel.EMAIL;
    }

    @Override
    public NotificationAttemptResult dispatchAttempt(NotificationJob job) {
        return emailTransport.send(job);
    }
}
