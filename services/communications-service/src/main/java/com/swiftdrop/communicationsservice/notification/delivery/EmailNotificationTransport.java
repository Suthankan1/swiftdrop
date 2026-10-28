package com.swiftdrop.communicationsservice.notification.delivery;

import com.swiftdrop.communicationsservice.client.AccountsRecipientContactClient;
import com.swiftdrop.communicationsservice.client.PermittedRecipientContact;
import com.swiftdrop.communicationsservice.consumer.NoEligibleNotificationTargetException;
import com.swiftdrop.communicationsservice.consumer.NotificationSchedulingService;
import com.swiftdrop.communicationsservice.consumer.RecipientNotFoundException;
import com.swiftdrop.communicationsservice.notification.NotificationChannel;
import com.swiftdrop.communicationsservice.notification.NotificationJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;

/**
 * First real notification transport for the EMAIL channel (Issue #117).
 * Connects directly to the local SMTP test sink (e.g. Mailpit localhost:1025)
 * without hardcoding credentials or exposing recipient PII in logs.
 *
 * Invariants:
 * 1. Resolves permitted recipient contact dynamically from Accounts Service via authenticated gRPC.
 * 2. Re-validates current recipient eligibility at the sending boundary.
 * 3. Never prints or logs plain email addresses or customer credentials.
 * 4. Strictly avoids persisting raw recipient email addresses in communications_db.
 */
@Component
public class EmailNotificationTransport {

    private static final Logger log = LoggerFactory.getLogger(EmailNotificationTransport.class);

    private final JavaMailSender mailSender;
    private final AccountsRecipientContactClient accountsContactClient;
    private final NotificationFailureClassifier failureClassifier;
    private final String fromAddress;

    @Autowired
    public EmailNotificationTransport(
            @Autowired(required = false) JavaMailSender mailSender,
            AccountsRecipientContactClient accountsContactClient,
            NotificationFailureClassifier failureClassifier,
            @Value("${swiftdrop.notifications.smtp.from:notifications@swiftdrop.com}") String fromAddress) {
        this.mailSender = mailSender;
        this.accountsContactClient = Objects.requireNonNull(accountsContactClient, "accountsContactClient cannot be null");
        this.failureClassifier = Objects.requireNonNull(failureClassifier, "failureClassifier cannot be null");
        this.fromAddress = (fromAddress != null && !fromAddress.isBlank()) ? fromAddress.trim() : "notifications@swiftdrop.com";
    }

    public NotificationChannel getChannel() {
        return NotificationChannel.EMAIL;
    }

    /**
     * Executes outbound delivery for a claimed notification job.
     *
     * @param job claimed notification job
     * @return structured attempt outcome
     */
    public NotificationAttemptResult send(NotificationJob job) {
        Objects.requireNonNull(job, "job cannot be null");
        long startMs = System.currentTimeMillis();

        if (job.getChannel() != NotificationChannel.EMAIL) {
            long duration = System.currentTimeMillis() - startMs;
            return NotificationAttemptResult.permanentFailure(
                    NotificationFailureClassifier.INVALID_CHANNEL,
                    "UNSUPPORTED_CHANNEL_" + job.getChannel(),
                    duration
            );
        }

        try {
            // 1. Resolve permitted recipient handle from Accounts Service
            String recipientSub = job.getRecipientSub();
            Optional<PermittedRecipientContact> contactOpt = accountsContactClient.resolveContact(
                    recipientSub,
                    NotificationSchedulingService.NOTIFICATION_PURPOSE,
                    NotificationChannel.EMAIL
            );

            if (contactOpt.isEmpty()) {
                long duration = System.currentTimeMillis() - startMs;
                log.warn("Recipient reference not found in Accounts at transport boundary [notificationId={}]",
                        job.getNotificationId());
                return NotificationAttemptResult.permanentFailure(
                        NotificationFailureClassifier.RECIPIENT_NOT_FOUND,
                        "RECIPIENT_NOT_FOUND",
                        duration
                );
            }

            PermittedRecipientContact contact = contactOpt.get();

            // 2. Re-validate current recipient eligibility
            if (!contact.isEligible() || !contact.hasPermittedEmail()) {
                long duration = System.currentTimeMillis() - startMs;
                log.warn("Recipient ineligible for email delivery at transport boundary [notificationId={}, reason={}]",
                        job.getNotificationId(), contact.rejectionReason());
                return NotificationAttemptResult.permanentFailure(
                        NotificationFailureClassifier.RECIPIENT_INELIGIBLE,
                        "RECIPIENT_INELIGIBLE",
                        duration
                );
            }

            String destinationEmail = contact.email();
            if (destinationEmail == null || destinationEmail.isBlank()) {
                long duration = System.currentTimeMillis() - startMs;
                return NotificationAttemptResult.permanentFailure(
                        NotificationFailureClassifier.RECIPIENT_INELIGIBLE,
                        "RESOLVED_EMAIL_BLANK",
                        duration
                );
            }

            // 3. Build approved notification message
            String template = job.getTemplate() != null ? job.getTemplate() : "PARCEL_STATUS_UPDATE";
            String subject = buildSubject(template);
            String body = buildBody(job, template);

            // 4. Submit message using configured SMTP transport
            if (mailSender == null) {
                long duration = System.currentTimeMillis() - startMs;
                log.error("JavaMailSender is not configured in this environment [notificationId={}]",
                        job.getNotificationId());
                return NotificationAttemptResult.transientFailure(
                        NotificationFailureClassifier.ACCOUNTS_UNAVAILABLE,
                        "MAIL_SENDER_NOT_CONFIGURED",
                        duration
                );
            }

            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromAddress);
            message.setTo(destinationEmail);
            message.setSubject(subject);
            message.setText(body);

            mailSender.send(message);

            long duration = System.currentTimeMillis() - startMs;
            log.info("Outbound email delivered to SMTP test sink [notificationId={}, durationMs={}]",
                    job.getNotificationId(), duration);

            return NotificationAttemptResult.success(duration);

        } catch (Exception ex) {
            long duration = System.currentTimeMillis() - startMs;
            log.warn("Outbound email delivery attempt failed [notificationId={}, error={}]",
                    job.getNotificationId(), ex.getMessage());
            return failureClassifier.classify(ex, duration);
        }
    }

    private String buildSubject(String template) {
        String cleanStatus = template.replace("PARCEL_", "");
        return "[SwiftDrop] Parcel Delivery Update: " + cleanStatus;
    }

    private String buildBody(NotificationJob job, String template) {
        String cleanStatus = template.replace("PARCEL_", "");
        return "Hello,\n\n"
                + "Your parcel status has been updated to: " + cleanStatus + ".\n"
                + "Tracking Reference: " + job.getParcelId() + "\n"
                + "Notification Reference: " + job.getNotificationId() + "\n\n"
                + "Thank you for using SwiftDrop.";
    }
}
