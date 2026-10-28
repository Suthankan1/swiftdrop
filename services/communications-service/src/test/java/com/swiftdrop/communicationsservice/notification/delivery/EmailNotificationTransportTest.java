package com.swiftdrop.communicationsservice.notification.delivery;

import com.swiftdrop.communicationsservice.client.AccountsRecipientContactClient;
import com.swiftdrop.communicationsservice.client.AccountsServiceUnavailableException;
import com.swiftdrop.communicationsservice.client.PermittedRecipientContact;
import com.swiftdrop.communicationsservice.consumer.NotificationSchedulingService;
import com.swiftdrop.communicationsservice.notification.NotificationChannel;
import com.swiftdrop.communicationsservice.notification.NotificationJob;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmailNotificationTransportTest {

    private JavaMailSender mailSender;
    private AccountsRecipientContactClient accountsContactClient;
    private NotificationFailureClassifier failureClassifier;
    private EmailNotificationTransport transport;

    private final String fromAddress = "notifications@swiftdrop.com";

    @BeforeEach
    void setUp() {
        mailSender = mock(JavaMailSender.class);
        accountsContactClient = mock(AccountsRecipientContactClient.class);
        failureClassifier = new NotificationFailureClassifier();

        transport = new EmailNotificationTransport(
                mailSender,
                accountsContactClient,
                failureClassifier,
                fromAddress
        );
    }

    @Test
    @DisplayName("Channel is EMAIL")
    void getChannel_returnsEmail() {
        assertThat(transport.getChannel()).isEqualTo(NotificationChannel.EMAIL);
    }

    @Test
    @DisplayName("When job channel is not EMAIL, returns permanent failure with invalid channel error")
    void send_whenChannelNotEmail_returnsPermanentFailure() {
        NotificationJob job = createJob(NotificationChannel.SMS_MOCK);

        NotificationAttemptResult result = transport.send(job);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.retryable()).isFalse();
        assertThat(result.outcome()).isEqualTo(NotificationAttemptResult.PERMANENT_FAILURE);
        assertThat(result.failureCategory()).isEqualTo(NotificationFailureClassifier.INVALID_CHANNEL);
    }

    @Test
    @DisplayName("When recipient not found in Accounts, returns permanent failure")
    void send_whenRecipientNotFound_returnsPermanentFailure() {
        NotificationJob job = createJob(NotificationChannel.EMAIL);

        when(accountsContactClient.resolveContact(
                eq(job.getRecipientSub()),
                eq(NotificationSchedulingService.NOTIFICATION_PURPOSE),
                eq(NotificationChannel.EMAIL)
        )).thenReturn(Optional.empty());

        NotificationAttemptResult result = transport.send(job);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.retryable()).isFalse();
        assertThat(result.outcome()).isEqualTo(NotificationAttemptResult.PERMANENT_FAILURE);
        assertThat(result.failureCategory()).isEqualTo(NotificationFailureClassifier.RECIPIENT_NOT_FOUND);
    }

    @Test
    @DisplayName("When recipient ineligible, returns permanent failure")
    void send_whenRecipientIneligible_returnsPermanentFailure() {
        NotificationJob job = createJob(NotificationChannel.EMAIL);

        PermittedRecipientContact ineligibleContact = new PermittedRecipientContact(
                job.getRecipientSub(),
                "customer@example.com",
                null,
                "Customer",
                false,
                false,
                false,
                "SUSPENDED",
                "EMAIL_NOT_VERIFIED"
        );

        when(accountsContactClient.resolveContact(
                eq(job.getRecipientSub()),
                eq(NotificationSchedulingService.NOTIFICATION_PURPOSE),
                eq(NotificationChannel.EMAIL)
        )).thenReturn(Optional.of(ineligibleContact));

        NotificationAttemptResult result = transport.send(job);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.retryable()).isFalse();
        assertThat(result.outcome()).isEqualTo(NotificationAttemptResult.PERMANENT_FAILURE);
        assertThat(result.failureCategory()).isEqualTo(NotificationFailureClassifier.RECIPIENT_INELIGIBLE);
    }

    @Test
    @DisplayName("When resolved email is blank, returns permanent failure")
    void send_whenResolvedEmailBlank_returnsPermanentFailure() {
        NotificationJob job = createJob(NotificationChannel.EMAIL);

        PermittedRecipientContact blankEmailContact = new PermittedRecipientContact(
                job.getRecipientSub(),
                "   ",
                null,
                "Customer",
                true,
                true,
                false,
                "ACTIVE",
                null
        );

        when(accountsContactClient.resolveContact(
                eq(job.getRecipientSub()),
                eq(NotificationSchedulingService.NOTIFICATION_PURPOSE),
                eq(NotificationChannel.EMAIL)
        )).thenReturn(Optional.of(blankEmailContact));

        NotificationAttemptResult result = transport.send(job);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.retryable()).isFalse();
        assertThat(result.outcome()).isEqualTo(NotificationAttemptResult.PERMANENT_FAILURE);
        assertThat(result.failureCategory()).isEqualTo(NotificationFailureClassifier.RECIPIENT_INELIGIBLE);
    }

    @Test
    @DisplayName("When JavaMailSender is null, returns transient failure")
    void send_whenMailSenderNull_returnsTransientFailure() {
        EmailNotificationTransport nullSenderTransport = new EmailNotificationTransport(
                null,
                accountsContactClient,
                failureClassifier,
                fromAddress
        );

        NotificationJob job = createJob(NotificationChannel.EMAIL);

        PermittedRecipientContact validContact = new PermittedRecipientContact(
                job.getRecipientSub(),
                "customer@example.com",
                null,
                "Customer",
                true,
                true,
                false,
                "ACTIVE",
                null
        );

        when(accountsContactClient.resolveContact(any(), any(), any()))
                .thenReturn(Optional.of(validContact));

        NotificationAttemptResult result = nullSenderTransport.send(job);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.retryable()).isTrue();
        assertThat(result.outcome()).isEqualTo(NotificationAttemptResult.TRANSIENT_FAILURE);
        assertThat(result.failureCategory()).isEqualTo(NotificationFailureClassifier.ACCOUNTS_UNAVAILABLE);
    }

    @Test
    @DisplayName("When valid recipient and sender available, dispatches email and returns success")
    void send_whenValid_dispatchesEmailAndReturnsSuccess() {
        NotificationJob job = createJob(NotificationChannel.EMAIL);

        PermittedRecipientContact validContact = new PermittedRecipientContact(
                job.getRecipientSub(),
                "customer@example.com",
                null,
                "Customer",
                true,
                true,
                false,
                "ACTIVE",
                null
        );

        when(accountsContactClient.resolveContact(
                eq(job.getRecipientSub()),
                eq(NotificationSchedulingService.NOTIFICATION_PURPOSE),
                eq(NotificationChannel.EMAIL)
        )).thenReturn(Optional.of(validContact));

        NotificationAttemptResult result = transport.send(job);

        assertThat(result.isSuccess()).isTrue();

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());

        SimpleMailMessage sent = captor.getValue();
        assertThat(sent.getFrom()).isEqualTo(fromAddress);
        assertThat(sent.getTo()).containsExactly("customer@example.com");
        assertThat(sent.getSubject()).contains("DELIVERED");
        assertThat(sent.getText()).contains("Your parcel status has been updated");
        assertThat(sent.getText()).contains(job.getParcelId().toString());
    }

    @Test
    @DisplayName("When JavaMailSender throws MailSendException with connection refused, classifies as SMTP_CONNECTION_TIMEOUT")
    void send_whenMailSendException_returnsTransientFailure() {
        NotificationJob job = createJob(NotificationChannel.EMAIL);

        PermittedRecipientContact validContact = new PermittedRecipientContact(
                job.getRecipientSub(),
                "customer@example.com",
                null,
                "Customer",
                true,
                true,
                false,
                "ACTIVE",
                null
        );

        when(accountsContactClient.resolveContact(any(), any(), any()))
                .thenReturn(Optional.of(validContact));

        doThrow(new MailSendException("SMTP connection refused"))
                .when(mailSender).send(any(SimpleMailMessage.class));

        NotificationAttemptResult result = transport.send(job);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.retryable()).isTrue();
        assertThat(result.failureCategory()).isEqualTo(NotificationFailureClassifier.SMTP_CONNECTION_TIMEOUT);
    }

    @Test
    @DisplayName("When accountsContactClient throws AccountsServiceUnavailableException, classifies as transient failure")
    void send_whenAccountsThrowsUnavailable_returnsTransientFailure() {
        NotificationJob job = createJob(NotificationChannel.EMAIL);

        when(accountsContactClient.resolveContact(any(), any(), any()))
                .thenThrow(new AccountsServiceUnavailableException("Accounts service offline", new RuntimeException()));

        NotificationAttemptResult result = transport.send(job);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.retryable()).isTrue();
        assertThat(result.failureCategory()).isEqualTo(NotificationFailureClassifier.ACCOUNTS_UNAVAILABLE);
    }

    private NotificationJob createJob(NotificationChannel channel) {
        return new NotificationJob(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "user-123",
                UUID.randomUUID(),
                channel,
                "sub:user-123",
                "PARCEL_DELIVERED"
        );
    }
}
