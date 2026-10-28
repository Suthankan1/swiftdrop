package com.swiftdrop.communicationsservice.notification.delivery;

import com.swiftdrop.communicationsservice.notification.NotificationChannel;
import com.swiftdrop.communicationsservice.notification.NotificationJob;
import com.swiftdrop.communicationsservice.notification.NotificationJobRepository;
import com.swiftdrop.communicationsservice.notification.NotificationStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationDeliverySchedulerTest {

    private NotificationJobRepository notificationJobRepository;
    private NotificationExecutionService executionService;
    private NotificationSchedulerProperties schedulerProperties;
    private NotificationDeliveryScheduler scheduler;

    private Instant testNow;
    private Clock fixedClock;

    @BeforeEach
    void setUp() {
        notificationJobRepository = mock(NotificationJobRepository.class);
        executionService = mock(NotificationExecutionService.class);

        schedulerProperties = new NotificationSchedulerProperties();
        schedulerProperties.setEnabled(true);
        schedulerProperties.setPollIntervalMs(5000L);
        schedulerProperties.setRecoveryIntervalMs(60000L);
        schedulerProperties.setLeaseTimeoutSeconds(300L);
        schedulerProperties.setBatchSize(50);

        testNow = Instant.parse("2026-10-27T12:00:00Z");
        fixedClock = Clock.fixed(testNow, ZoneOffset.UTC);

        scheduler = new NotificationDeliveryScheduler(
                notificationJobRepository,
                executionService,
                schedulerProperties,
                fixedClock
        );
    }

    @Test
    @DisplayName("When scheduler is disabled, schedulePendingNotifications skips polling and returns 0")
    void schedulePendingNotifications_whenDisabled_returnsZeroAndDoesNotPoll() {
        schedulerProperties.setEnabled(false);

        int result = scheduler.schedulePendingNotifications();

        assertThat(result).isZero();
        verify(notificationJobRepository, never()).findDueNotifications(any(), any(), any());
    }

    @Test
    @DisplayName("When scheduler is stopped, schedulePendingNotifications skips polling and returns 0")
    void schedulePendingNotifications_whenStopped_returnsZero() {
        scheduler.stop();

        int result = scheduler.schedulePendingNotifications();

        assertThat(result).isZero();
        verify(notificationJobRepository, never()).findDueNotifications(any(), any(), any());
    }

    @Test
    @DisplayName("When no due jobs exist, schedulePendingNotifications returns 0")
    void schedulePendingNotifications_whenNoDueJobs_returnsZero() {
        when(notificationJobRepository.findDueNotifications(eq(NotificationStatus.PENDING), eq(testNow), any(Pageable.class)))
                .thenReturn(List.of());

        int result = scheduler.schedulePendingNotifications();

        assertThat(result).isZero();
        verify(executionService, never()).tryClaimJob(any(), anyString(), any(), any());
    }

    @Test
    @DisplayName("When due jobs exist, claims and executes each successfully")
    void schedulePendingNotifications_whenDueJobsPresent_claimsAndExecutesEach() {
        UUID job1Id = UUID.randomUUID();
        UUID job2Id = UUID.randomUUID();
        NotificationJob job1 = createJob(job1Id);
        NotificationJob job2 = createJob(job2Id);

        when(notificationJobRepository.findDueNotifications(eq(NotificationStatus.PENDING), eq(testNow), any(Pageable.class)))
                .thenReturn(List.of(job1, job2));

        when(executionService.tryClaimJob(eq(job1Id), anyString(), eq(testNow), any(Instant.class)))
                .thenReturn(true);
        when(executionService.tryClaimJob(eq(job2Id), anyString(), eq(testNow), any(Instant.class)))
                .thenReturn(true);

        int executed = scheduler.schedulePendingNotifications();

        assertThat(executed).isEqualTo(2);
        verify(executionService).executeClaimedNotification(eq(job1Id), anyString());
        verify(executionService).executeClaimedNotification(eq(job2Id), anyString());
    }

    @Test
    @DisplayName("When tryClaimJob returns false, skips execution of that job")
    void schedulePendingNotifications_whenClaimFails_skipsExecution() {
        UUID job1Id = UUID.randomUUID();
        NotificationJob job1 = createJob(job1Id);

        when(notificationJobRepository.findDueNotifications(eq(NotificationStatus.PENDING), eq(testNow), any(Pageable.class)))
                .thenReturn(List.of(job1));

        when(executionService.tryClaimJob(eq(job1Id), anyString(), eq(testNow), any(Instant.class)))
                .thenReturn(false);

        int executed = scheduler.schedulePendingNotifications();

        assertThat(executed).isZero();
        verify(executionService, never()).executeClaimedNotification(eq(job1Id), anyString());
    }

    @Test
    @DisplayName("When execution throws an unexpected error, continues with remaining jobs")
    void schedulePendingNotifications_whenExecutionThrows_continuesProcessing() {
        UUID job1Id = UUID.randomUUID();
        UUID job2Id = UUID.randomUUID();
        NotificationJob job1 = createJob(job1Id);
        NotificationJob job2 = createJob(job2Id);

        when(notificationJobRepository.findDueNotifications(eq(NotificationStatus.PENDING), eq(testNow), any(Pageable.class)))
                .thenReturn(List.of(job1, job2));

        when(executionService.tryClaimJob(any(UUID.class), anyString(), eq(testNow), any(Instant.class)))
                .thenReturn(true);

        doThrow(new RuntimeException("Simulated unexpected crash"))
                .when(executionService).executeClaimedNotification(eq(job1Id), anyString());

        int executed = scheduler.schedulePendingNotifications();

        assertThat(executed).isEqualTo(1);
        verify(executionService).executeClaimedNotification(eq(job1Id), anyString());
        verify(executionService).executeClaimedNotification(eq(job2Id), anyString());
    }

    @Test
    @DisplayName("When recovery is disabled, recoverStalledNotifications returns 0")
    void recoverStalledNotifications_whenDisabled_returnsZero() {
        schedulerProperties.setEnabled(false);

        int recovered = scheduler.recoverStalledNotifications();

        assertThat(recovered).isZero();
        verify(executionService, never()).recoverStuckProcessingJobs(any());
    }

    @Test
    @DisplayName("When recoverStuckProcessingJobs recovers jobs, returns the count")
    void recoverStalledNotifications_whenRecovered_returnsCount() {
        when(executionService.recoverStuckProcessingJobs(Duration.ofSeconds(300L)))
                .thenReturn(3);

        int recovered = scheduler.recoverStalledNotifications();

        assertThat(recovered).isEqualTo(3);
        verify(executionService).recoverStuckProcessingJobs(Duration.ofSeconds(300L));
    }

    @Test
    @DisplayName("When recoverStuckProcessingJobs throws an exception, logs and returns 0")
    void recoverStalledNotifications_whenExceptionThrown_returnsZero() {
        when(executionService.recoverStuckProcessingJobs(any()))
                .thenThrow(new RuntimeException("DB connection error"));

        int recovered = scheduler.recoverStalledNotifications();

        assertThat(recovered).isZero();
    }

    private NotificationJob createJob(UUID id) {
        NotificationJob job = new NotificationJob(
                id,
                UUID.randomUUID(),
                "user-123",
                UUID.randomUUID(),
                NotificationChannel.EMAIL,
                "sub:user-123",
                "PARCEL_DELIVERED"
        );
        job.setStatus(NotificationStatus.PENDING);
        job.setAttempts(0);
        job.setCreatedAt(testNow);
        job.setNextAttemptAt(testNow);
        return job;
    }
}
