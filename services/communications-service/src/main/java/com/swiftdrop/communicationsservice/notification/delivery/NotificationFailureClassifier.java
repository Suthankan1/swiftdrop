package com.swiftdrop.communicationsservice.notification.delivery;

import com.swiftdrop.communicationsservice.client.AccountsSecurityException;
import com.swiftdrop.communicationsservice.client.AccountsServiceUnavailableException;
import com.swiftdrop.communicationsservice.client.DeliveryAuthorizationException;
import com.swiftdrop.communicationsservice.client.DeliverySecurityException;
import com.swiftdrop.communicationsservice.client.DeliveryServiceUnavailableException;
import com.swiftdrop.communicationsservice.consumer.NoEligibleNotificationTargetException;
import com.swiftdrop.communicationsservice.consumer.RecipientNotFoundException;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.regex.Pattern;

/**
 * Classifies outbound notification delivery failures into safe, bounded categories
 * and determines whether the condition is transient (retryable) or permanent/security (fail-closed).
 *
 * Invariant:
 * Zero recipient email addresses, passwords, or raw provider stacktraces are exposed.
 */
@Component
public class NotificationFailureClassifier {

    public static final String SMTP_CONNECTION_TIMEOUT = "SMTP_CONNECTION_TIMEOUT";
    public static final String SMTP_TEMPORARY_ERROR = "SMTP_TEMPORARY_ERROR";
    public static final String SMTP_PERMANENT_ERROR = "SMTP_PERMANENT_ERROR";
    public static final String SMTP_AUTH_FAILED = "SMTP_AUTH_FAILED";
    public static final String ACCOUNTS_UNAVAILABLE = "ACCOUNTS_UNAVAILABLE";
    public static final String DELIVERY_UNAVAILABLE = "DELIVERY_UNAVAILABLE";
    public static final String SERVICE_AUTH_FAILED = "SERVICE_AUTH_FAILED";
    public static final String RECIPIENT_NOT_FOUND = "RECIPIENT_NOT_FOUND";
    public static final String RECIPIENT_INELIGIBLE = "RECIPIENT_INELIGIBLE";
    public static final String UNAUTHORIZED_RECIPIENT = "UNAUTHORIZED_RECIPIENT";
    public static final String INVALID_CHANNEL = "INVALID_CHANNEL";
    public static final String TEMPLATE_ERROR = "TEMPLATE_ERROR";
    public static final String NETWORK_ERROR = "NETWORK_ERROR";
    public static final String UNEXPECTED_ERROR = "UNEXPECTED_ERROR";

    private static final Pattern SENSITIVE_PATTERN = Pattern.compile("(?i)(password|secret|bearer|token|key)=\\S+");

    public NotificationAttemptResult classify(Throwable throwable, long durationMs) {
        if (throwable == null) {
            return NotificationAttemptResult.success(durationMs);
        }

        Throwable root = findRootCause(throwable);

        // 1. Upstream service unavailability (Transient - Retryable)
        // Checked first before base DeliveryAuthorizationException
        if (throwable instanceof AccountsServiceUnavailableException || root instanceof AccountsServiceUnavailableException) {
            return NotificationAttemptResult.transientFailure(ACCOUNTS_UNAVAILABLE, "ACCOUNTS_SERVICE_UNAVAILABLE", durationMs);
        }
        if (throwable instanceof DeliveryServiceUnavailableException || root instanceof DeliveryServiceUnavailableException) {
            return NotificationAttemptResult.transientFailure(DELIVERY_UNAVAILABLE, "DELIVERY_SERVICE_UNAVAILABLE", durationMs);
        }

        // 2. Service-to-service security errors (Fail Closed)
        // Checked before base DeliveryAuthorizationException
        if (throwable instanceof AccountsSecurityException || root instanceof AccountsSecurityException ||
            throwable instanceof DeliverySecurityException || root instanceof DeliverySecurityException) {
            return NotificationAttemptResult.securityFailure(SERVICE_AUTH_FAILED, "MACHINE_AUTH_REJECTED", durationMs);
        }

        // 3. Recipient and authorization errors (Permanent - Fail Closed)
        if (throwable instanceof RecipientNotFoundException || root instanceof RecipientNotFoundException) {
            return NotificationAttemptResult.permanentFailure(RECIPIENT_NOT_FOUND, "RECIPIENT_NOT_FOUND", durationMs);
        }
        if (throwable instanceof NoEligibleNotificationTargetException || root instanceof NoEligibleNotificationTargetException) {
            return NotificationAttemptResult.permanentFailure(RECIPIENT_INELIGIBLE, "RECIPIENT_INELIGIBLE", durationMs);
        }
        if (throwable instanceof DeliveryAuthorizationException || root instanceof DeliveryAuthorizationException) {
            return NotificationAttemptResult.permanentFailure(UNAUTHORIZED_RECIPIENT, "DELIVERY_AUTHORIZATION_REJECTED", durationMs);
        }

        // 4. Mail Transport Authentication (Permanent)
        if (throwable instanceof MailAuthenticationException || root instanceof MailAuthenticationException) {
            return NotificationAttemptResult.permanentFailure(SMTP_AUTH_FAILED, "SMTP_AUTHENTICATION_FAILED", durationMs);
        }

        // 5. Network timeouts and socket disconnects (Transient)
        if (root instanceof SocketTimeoutException) {
            return NotificationAttemptResult.timeout(SMTP_CONNECTION_TIMEOUT, "SOCKET_TIMEOUT", durationMs);
        }
        if (root instanceof ConnectException) {
            return NotificationAttemptResult.transientFailure(SMTP_CONNECTION_TIMEOUT, "CONNECTION_REFUSED", durationMs);
        }
        if (root instanceof SocketException || root instanceof IOException) {
            return NotificationAttemptResult.transientFailure(NETWORK_ERROR, "IO_SOCKET_EXCEPTION", durationMs);
        }

        // 6. Template or channel validation
        if (throwable instanceof IllegalArgumentException) {
            String msg = throwable.getMessage() != null ? throwable.getMessage().toLowerCase() : "";
            if (msg.contains("template")) {
                return NotificationAttemptResult.permanentFailure(TEMPLATE_ERROR, "INVALID_TEMPLATE", durationMs);
            }
            if (msg.contains("channel")) {
                return NotificationAttemptResult.permanentFailure(INVALID_CHANNEL, "UNSUPPORTED_CHANNEL", durationMs);
            }
        }

        // 7. General MailSendException analysis
        if (throwable instanceof MailSendException mailEx) {
            String msg = mailEx.getMessage() != null ? mailEx.getMessage().toLowerCase() : "";
            if (msg.contains("timeout") || msg.contains("timed out")) {
                return NotificationAttemptResult.timeout(SMTP_CONNECTION_TIMEOUT, "SMTP_TIMEOUT", durationMs);
            }
            if (msg.contains("connection refused") || msg.contains("connect")) {
                return NotificationAttemptResult.transientFailure(SMTP_CONNECTION_TIMEOUT, "SMTP_CONNECT_FAILED", durationMs);
            }
            if (msg.contains("550") || msg.contains("551") || msg.contains("554") || msg.contains("user unknown") || msg.contains("mailbox unavailable")) {
                return NotificationAttemptResult.permanentFailure(SMTP_PERMANENT_ERROR, "SMTP_5XX_PERMANENT_REJECTION", durationMs);
            }
            if (msg.contains("421") || msg.contains("450") || msg.contains("451") || msg.contains("452")) {
                return NotificationAttemptResult.transientFailure(SMTP_TEMPORARY_ERROR, "SMTP_4XX_TEMPORARY_FAILURE", durationMs);
            }
            return NotificationAttemptResult.transientFailure(SMTP_TEMPORARY_ERROR, "SMTP_SEND_FAILED", durationMs);
        }

        // 8. Fallback
        String sanitizedMessage = sanitize(throwable.getMessage());
        return NotificationAttemptResult.transientFailure(UNEXPECTED_ERROR, sanitizedMessage, durationMs);
    }

    private Throwable findRootCause(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }

    private String sanitize(String message) {
        if (message == null || message.isBlank()) {
            return "UNSPECIFIED_ERROR";
        }
        String redacted = SENSITIVE_PATTERN.matcher(message).replaceAll("$1=***");
        if (redacted.length() > 80) {
            redacted = redacted.substring(0, 80);
        }
        return redacted.replaceAll("[^a-zA-Z0-9_.-]", "_");
    }
}
