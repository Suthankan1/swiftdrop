package com.swiftdrop.communicationsservice.notification.service;

import com.swiftdrop.communicationsservice.client.AccountsRecipientContactClient;
import com.swiftdrop.communicationsservice.client.DeliveryAuthorizationException;
import com.swiftdrop.communicationsservice.client.DeliveryEventAuthorization;
import com.swiftdrop.communicationsservice.client.DeliveryRecipientAuthorizationClient;
import com.swiftdrop.communicationsservice.client.PermittedRecipientContact;
import com.swiftdrop.communicationsservice.consumer.NoEligibleNotificationTargetException;
import com.swiftdrop.communicationsservice.consumer.NotificationSchedulingService;
import com.swiftdrop.communicationsservice.metrics.CommunicationsMetrics;
import com.swiftdrop.communicationsservice.notification.NotificationChannel;
import com.swiftdrop.communicationsservice.notification.NotificationJob;
import com.swiftdrop.communicationsservice.notification.NotificationJobRepository;
import com.swiftdrop.communicationsservice.notification.NotificationReplayAudit;
import com.swiftdrop.communicationsservice.notification.NotificationReplayAuditRepository;
import com.swiftdrop.communicationsservice.notification.NotificationStatus;
import com.swiftdrop.communicationsservice.notification.exception.InvalidNotificationReplayException;
import com.swiftdrop.communicationsservice.notification.exception.NotificationNotFoundException;
import com.swiftdrop.security.jwt.SwiftDropRoles;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationReplayServiceTest {

    private NotificationJobRepository notificationJobRepository;
    private NotificationReplayAuditRepository replayAuditRepository;
    private AccountsRecipientContactClient accountsContactClient;
    private DeliveryRecipientAuthorizationClient deliveryAuthorizationClient;
    private CommunicationsMetrics communicationsMetrics;
    private NotificationReplayService replayService;

    @BeforeEach
    void setUp() {
        notificationJobRepository = mock(NotificationJobRepository.class);
        replayAuditRepository = mock(NotificationReplayAuditRepository.class);
        accountsContactClient = mock(AccountsRecipientContactClient.class);
        deliveryAuthorizationClient = mock(DeliveryRecipientAuthorizationClient.class);
        communicationsMetrics = mock(CommunicationsMetrics.class);

        replayService = new NotificationReplayService(
                notificationJobRepository,
                replayAuditRepository,
                accountsContactClient,
                deliveryAuthorizationClient
        );
        replayService.setCommunicationsMetrics(communicationsMetrics);
    }

    @Test
    @DisplayName("Replay rejects request when actor subject is missing")
    void replayNotification_whenActorSubMissing_throwsSecurityException() {
        UUID jobId = UUID.randomUUID();

        assertThatThrownBy(() -> replayService.replayNotification(
                jobId, null, SwiftDropRoles.ROLE_ADMIN, "Retry after transient issue"
        )).isInstanceOf(SecurityException.class)
          .hasMessageContaining("actor subject is missing");
    }

    @Test
    @DisplayName("Replay rejects unauthorized actor role")
    void replayNotification_whenUnauthorizedRole_throwsSecurityException() {
        UUID jobId = UUID.randomUUID();

        assertThatThrownBy(() -> replayService.replayNotification(
                jobId, "user-123", "ROLE_MERCHANT", "Retry after transient issue"
        )).isInstanceOf(SecurityException.class)
          .hasMessageContaining("not authorized");

        verify(communicationsMetrics).recordNotificationReplay("rejected");
    }

    @Test
    @DisplayName("Replay rejects blank reason")
    void replayNotification_whenReasonBlank_throwsIllegalArgumentException() {
        UUID jobId = UUID.randomUUID();

        assertThatThrownBy(() -> replayService.replayNotification(
                jobId, "admin-1", SwiftDropRoles.ROLE_ADMIN, "   "
        )).isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("must not be blank");
    }

    @Test
    @DisplayName("Replay throws NotificationNotFoundException when job does not exist")
    void replayNotification_whenJobNotFound_throwsException() {
        UUID jobId = UUID.randomUUID();
        when(notificationJobRepository.findById(jobId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> replayService.replayNotification(
                jobId, "admin-1", SwiftDropRoles.ROLE_ADMIN, "Valid reason"
        )).isInstanceOf(NotificationNotFoundException.class)
          .hasMessageContaining("Notification job not found");
    }

    @Test
    @DisplayName("Replay rejects job when status is not FAILED or EXHAUSTED")
    void replayNotification_whenJobNotTerminal_throwsInvalidNotificationReplayException() {
        UUID jobId = UUID.randomUUID();
        NotificationJob job = createJob(jobId, NotificationStatus.SENT);
        when(notificationJobRepository.findById(jobId)).thenReturn(Optional.of(job));

        assertThatThrownBy(() -> replayService.replayNotification(
                jobId, "admin-1", SwiftDropRoles.ROLE_ADMIN, "Valid reason"
        )).isInstanceOf(InvalidNotificationReplayException.class)
          .hasMessageContaining("cannot be replayed from status SENT");

        verify(communicationsMetrics).recordNotificationReplay("rejected");
    }

    @Test
    @DisplayName("Replay records REJECTED audit and throws exception when recipient is ineligible")
    void replayNotification_whenRecipientIneligible_recordsAuditAndThrowsException() {
        UUID jobId = UUID.randomUUID();
        NotificationJob job = createJob(jobId, NotificationStatus.EXHAUSTED);
        when(notificationJobRepository.findById(jobId)).thenReturn(Optional.of(job));

        when(accountsContactClient.resolveContact(
                eq(job.getRecipientSub()),
                eq(NotificationSchedulingService.NOTIFICATION_PURPOSE),
                eq(job.getChannel())
        )).thenReturn(Optional.of(new PermittedRecipientContact(
                job.getRecipientSub(),
                "ineligible@example.com",
                null,
                "Customer",
                false,
                false,
                false,
                "SUSPENDED",
                "OPTED_OUT"
        )));

        assertThatThrownBy(() -> replayService.replayNotification(
                jobId, "admin-1", SwiftDropRoles.ROLE_ADMIN, "Valid reason"
        )).isInstanceOf(NoEligibleNotificationTargetException.class)
          .hasMessageContaining("OPTED_OUT");

        ArgumentCaptor<NotificationReplayAudit> auditCaptor = ArgumentCaptor.forClass(NotificationReplayAudit.class);
        verify(replayAuditRepository).save(auditCaptor.capture());
        assertThat(auditCaptor.getValue().getDecision()).isEqualTo("REJECTED");
        assertThat(auditCaptor.getValue().getNotificationId()).isEqualTo(jobId);
        verify(communicationsMetrics).recordNotificationReplay("rejected");
        verify(notificationJobRepository, never()).save(any());
    }

    @Test
    @DisplayName("Replay records REJECTED audit and throws exception when Delivery authorization fails")
    void replayNotification_whenDeliveryAuthFails_recordsAuditAndThrowsException() {
        UUID jobId = UUID.randomUUID();
        NotificationJob job = createJob(jobId, NotificationStatus.FAILED);
        when(notificationJobRepository.findById(jobId)).thenReturn(Optional.of(job));

        when(accountsContactClient.resolveContact(
                eq(job.getRecipientSub()),
                eq(NotificationSchedulingService.NOTIFICATION_PURPOSE),
                eq(job.getChannel())
        )).thenReturn(Optional.of(new PermittedRecipientContact(
                job.getRecipientSub(),
                "customer@example.com",
                null,
                "Customer",
                true,
                true,
                false,
                "ACTIVE",
                null
        )));

        when(deliveryAuthorizationClient.authorizeEventRecipient(
                eq(job.getEventId()),
                eq(job.getParcelId()),
                any(),
                any(),
                eq(false)
        )).thenReturn(new DeliveryEventAuthorization(
                false,
                job.getParcelId(),
                job.getEventId(),
                job.getRecipientSub(),
                UUID.randomUUID(),
                "TRK-12345",
                "IN_TRANSIT",
                "PARCEL_STATUS_CHANGED",
                1L,
                1L,
                false,
                false,
                "EVENT_NOT_OWNED_BY_RECIPIENT"
        ));

        assertThatThrownBy(() -> replayService.replayNotification(
                jobId, "admin-1", SwiftDropRoles.ROLE_ADMIN, "Valid reason"
        )).isInstanceOf(DeliveryAuthorizationException.class)
          .hasMessageContaining("EVENT_NOT_OWNED_BY_RECIPIENT");

        ArgumentCaptor<NotificationReplayAudit> auditCaptor = ArgumentCaptor.forClass(NotificationReplayAudit.class);
        verify(replayAuditRepository).save(auditCaptor.capture());
        assertThat(auditCaptor.getValue().getDecision()).isEqualTo("REJECTED");
        verify(communicationsMetrics).recordNotificationReplay("rejected");
        verify(notificationJobRepository, never()).save(any());
    }

    @Test
    @DisplayName("Replay accepts authorized, eligible job, audits ACCEPTED, and resets job to PENDING")
    void replayNotification_whenValid_auditsAcceptedAndResetsToPending() {
        UUID jobId = UUID.randomUUID();
        NotificationJob job = createJob(jobId, NotificationStatus.EXHAUSTED);
        job.setAttempts(5);
        job.setClaimToken("old-token");

        when(notificationJobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(notificationJobRepository.save(any(NotificationJob.class))).thenAnswer(inv -> inv.getArgument(0));

        when(accountsContactClient.resolveContact(
                eq(job.getRecipientSub()),
                eq(NotificationSchedulingService.NOTIFICATION_PURPOSE),
                eq(job.getChannel())
        )).thenReturn(Optional.of(new PermittedRecipientContact(
                job.getRecipientSub(),
                "customer@example.com",
                null,
                "Customer",
                true,
                true,
                false,
                "ACTIVE",
                null
        )));

        when(deliveryAuthorizationClient.authorizeEventRecipient(
                eq(job.getEventId()),
                eq(job.getParcelId()),
                any(),
                any(),
                eq(false)
        )).thenReturn(new DeliveryEventAuthorization(
                true,
                job.getParcelId(),
                job.getEventId(),
                job.getRecipientSub(),
                UUID.randomUUID(),
                "TRK-12345",
                "DELIVERED",
                "PARCEL_STATUS_CHANGED",
                1L,
                1L,
                false,
                false,
                "AUTHORIZED"
        ));

        NotificationJob replayed = replayService.replayNotification(
                jobId, "admin-1", SwiftDropRoles.ROLE_ADMIN, "Manual operator approved retry"
        );

        assertThat(replayed.getStatus()).isEqualTo(NotificationStatus.PENDING);
        assertThat(replayed.getAttempts()).isZero();
        assertThat(replayed.getClaimToken()).isNull();
        assertThat(replayed.getLeaseExpiresAt()).isNull();

        ArgumentCaptor<NotificationReplayAudit> auditCaptor = ArgumentCaptor.forClass(NotificationReplayAudit.class);
        verify(replayAuditRepository).save(auditCaptor.capture());
        NotificationReplayAudit audit = auditCaptor.getValue();
        assertThat(audit.getDecision()).isEqualTo("ACCEPTED");
        assertThat(audit.getActorSub()).isEqualTo("admin-1");
        assertThat(audit.getActorRole()).isEqualTo(SwiftDropRoles.ROLE_ADMIN);
        assertThat(audit.getReason()).isEqualTo("Manual operator approved retry");
        assertThat(audit.getPreviousStatus()).isEqualTo("EXHAUSTED");

        verify(communicationsMetrics).recordNotificationReplay("succeeded");
    }

    private NotificationJob createJob(UUID id, NotificationStatus status) {
        NotificationJob job = new NotificationJob(
                id,
                UUID.randomUUID(),
                "user-123",
                UUID.randomUUID(),
                NotificationChannel.EMAIL,
                "sub:user-123",
                "PARCEL_DELIVERED"
        );
        job.setStatus(status);
        job.setAttempts(3);
        job.setCreatedAt(Instant.now());
        job.setNextAttemptAt(Instant.now());
        return job;
    }
}
