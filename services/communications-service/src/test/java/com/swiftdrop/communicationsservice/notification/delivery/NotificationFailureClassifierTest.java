package com.swiftdrop.communicationsservice.notification.delivery;

import com.swiftdrop.communicationsservice.client.AccountsSecurityException;
import com.swiftdrop.communicationsservice.client.AccountsServiceUnavailableException;
import com.swiftdrop.communicationsservice.client.DeliveryAuthorizationException;
import com.swiftdrop.communicationsservice.client.DeliverySecurityException;
import com.swiftdrop.communicationsservice.client.DeliveryServiceUnavailableException;
import com.swiftdrop.communicationsservice.consumer.NoEligibleNotificationTargetException;
import com.swiftdrop.communicationsservice.consumer.RecipientNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationFailureClassifierTest {

    private final NotificationFailureClassifier classifier = new NotificationFailureClassifier();

    @Test
    @DisplayName("Null throwable classifies as success")
    void testClassify_Null_Success() {
        NotificationAttemptResult result = classifier.classify(null, 50);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.outcome()).isEqualTo(NotificationAttemptResult.SUCCESS);
        assertThat(result.retryable()).isFalse();
        assertThat(result.durationMs()).isEqualTo(50);
    }

    @Test
    @DisplayName("Transient upstream errors classify as retryable")
    void testClassify_TransientUpstream_Retryable() {
        NotificationAttemptResult accountsDown = classifier.classify(
                new AccountsServiceUnavailableException("Accounts gRPC unavailable"), 100);
        assertThat(accountsDown.retryable()).isTrue();
        assertThat(accountsDown.outcome()).isEqualTo(NotificationAttemptResult.TRANSIENT_FAILURE);
        assertThat(accountsDown.failureCategory()).isEqualTo(NotificationFailureClassifier.ACCOUNTS_UNAVAILABLE);

        NotificationAttemptResult deliveryDown = classifier.classify(
                new DeliveryServiceUnavailableException("Delivery gRPC deadline exceeded"), 120);
        assertThat(deliveryDown.retryable()).isTrue();
        assertThat(deliveryDown.outcome()).isEqualTo(NotificationAttemptResult.TRANSIENT_FAILURE);
        assertThat(deliveryDown.failureCategory()).isEqualTo(NotificationFailureClassifier.DELIVERY_UNAVAILABLE);
    }

    @Test
    @DisplayName("Network timeout and connect failures classify as transient timeout")
    void testClassify_NetworkErrors_Transient() {
        NotificationAttemptResult timeout = classifier.classify(
                new IOException("Read timed out", new SocketTimeoutException("Read timed out")), 200);
        assertThat(timeout.retryable()).isTrue();
        assertThat(timeout.outcome()).isEqualTo(NotificationAttemptResult.TIMEOUT);
        assertThat(timeout.failureCategory()).isEqualTo(NotificationFailureClassifier.SMTP_CONNECTION_TIMEOUT);

        NotificationAttemptResult refused = classifier.classify(
                new MailSendException("Mail send failed", new ConnectException("Connection refused")), 150);
        assertThat(refused.retryable()).isTrue();
        assertThat(refused.outcome()).isEqualTo(NotificationAttemptResult.TRANSIENT_FAILURE);
        assertThat(refused.failureCategory()).isEqualTo(NotificationFailureClassifier.SMTP_CONNECTION_TIMEOUT);
    }

    @Test
    @DisplayName("Recipient ineligible or not found classifies as permanent fail-closed")
    void testClassify_RecipientErrors_Permanent() {
        NotificationAttemptResult notFound = classifier.classify(
                new RecipientNotFoundException("Recipient sub-1 not found"), 30);
        assertThat(notFound.retryable()).isFalse();
        assertThat(notFound.outcome()).isEqualTo(NotificationAttemptResult.PERMANENT_FAILURE);
        assertThat(notFound.failureCategory()).isEqualTo(NotificationFailureClassifier.RECIPIENT_NOT_FOUND);

        NotificationAttemptResult ineligible = classifier.classify(
                new NoEligibleNotificationTargetException("Recipient opted out of email"), 40);
        assertThat(ineligible.retryable()).isFalse();
        assertThat(ineligible.outcome()).isEqualTo(NotificationAttemptResult.PERMANENT_FAILURE);
        assertThat(ineligible.failureCategory()).isEqualTo(NotificationFailureClassifier.RECIPIENT_INELIGIBLE);

        NotificationAttemptResult unauthorized = classifier.classify(
                new DeliveryAuthorizationException("Parcel recipient mismatch"), 45);
        assertThat(unauthorized.retryable()).isFalse();
        assertThat(unauthorized.outcome()).isEqualTo(NotificationAttemptResult.PERMANENT_FAILURE);
        assertThat(unauthorized.failureCategory()).isEqualTo(NotificationFailureClassifier.UNAUTHORIZED_RECIPIENT);
    }

    @Test
    @DisplayName("Security authentication errors classify as fail-closed security failure")
    void testClassify_SecurityErrors_FailClosed() {
        NotificationAttemptResult accountsSec = classifier.classify(
                new AccountsSecurityException("Invalid mTLS or service credentials"), 80);
        assertThat(accountsSec.retryable()).isFalse();
        assertThat(accountsSec.outcome()).isEqualTo(NotificationAttemptResult.SECURITY_FAILURE);
        assertThat(accountsSec.failureCategory()).isEqualTo(NotificationFailureClassifier.SERVICE_AUTH_FAILED);

        NotificationAttemptResult deliverySec = classifier.classify(
                new DeliverySecurityException("Caller not authorized"), 85);
        assertThat(deliverySec.retryable()).isFalse();
        assertThat(deliverySec.outcome()).isEqualTo(NotificationAttemptResult.SECURITY_FAILURE);
        assertThat(deliverySec.failureCategory()).isEqualTo(NotificationFailureClassifier.SERVICE_AUTH_FAILED);
    }

    @Test
    @DisplayName("SMTP 5xx and authentication rejections classify as permanent failure")
    void testClassify_SmtpPermanent_FailClosed() {
        NotificationAttemptResult smtpAuth = classifier.classify(
                new MailAuthenticationException("535 Authentication credentials invalid"), 90);
        assertThat(smtpAuth.retryable()).isFalse();
        assertThat(smtpAuth.outcome()).isEqualTo(NotificationAttemptResult.PERMANENT_FAILURE);
        assertThat(smtpAuth.failureCategory()).isEqualTo(NotificationFailureClassifier.SMTP_AUTH_FAILED);

        NotificationAttemptResult smtp550 = classifier.classify(
                new MailSendException("550 5.1.1 User unknown"), 95);
        assertThat(smtp550.retryable()).isFalse();
        assertThat(smtp550.outcome()).isEqualTo(NotificationAttemptResult.PERMANENT_FAILURE);
        assertThat(smtp550.failureCategory()).isEqualTo(NotificationFailureClassifier.SMTP_PERMANENT_ERROR);
    }

    @Test
    @DisplayName("Sanitizer strips sensitive credentials and formats safely")
    void testSanitizer_ZeroSecrets() {
        NotificationAttemptResult result = classifier.classify(
                new RuntimeException("Error connecting with password=superSecretPassword123 and token=xyzSecret"), 10);
        assertThat(result.safeErrorCode()).doesNotContain("superSecretPassword123");
        assertThat(result.safeErrorCode()).doesNotContain("xyzSecret");
    }
}
