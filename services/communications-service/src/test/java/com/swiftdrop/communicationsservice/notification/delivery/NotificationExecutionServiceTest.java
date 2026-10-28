package com.swiftdrop.communicationsservice.notification.delivery;

import com.swiftdrop.communicationsservice.notification.NotificationAttempt;
import com.swiftdrop.communicationsservice.notification.NotificationAttemptRepository;
import com.swiftdrop.communicationsservice.notification.NotificationChannel;
import com.swiftdrop.communicationsservice.notification.NotificationDispatchAdapter;
import com.swiftdrop.communicationsservice.notification.NotificationJob;
import com.swiftdrop.communicationsservice.notification.NotificationJobRepository;
import com.swiftdrop.communicationsservice.notification.NotificationStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationExecutionServiceTest {

    @Mock
    private NotificationJobRepository notificationJobRepository;

    @Mock
    private NotificationAttemptRepository notificationAttemptRepository;

    @Mock
    private NotificationDispatchAdapter emailAdapter;

    private NotificationRetryProperties retryProperties;
    private NotificationFailureClassifier failureClassifier;
    private Clock fixedClock;
    private NotificationExecutionService executionService;

    private final Instant fixedNow = Instant.parse("2026-10-28T10:00:00Z");
    private final UUID notificationId = UUID.randomUUID();
    private final String claimToken = "worker-token-123";

    @BeforeEach
    void setUp() {
        when(emailAdapter.getChannel()).thenReturn(NotificationChannel.EMAIL);

        retryProperties = new NotificationRetryProperties();
        retryProperties.setMaxAttempts(3);
        retryProperties.setBaseBackoffSeconds(10);
        retryProperties.setMaxBackoffSeconds(100);
        retryProperties.setJitterEnabled(false);

        failureClassifier = new NotificationFailureClassifier();
        fixedClock = Clock.fixed(fixedNow, ZoneOffset.UTC);

        executionService = new NotificationExecutionService(
                notificationJobRepository,
                notificationAttemptRepository,
                List.of(emailAdapter),
                retryProperties,
                failureClassifier,
                fixedClock,
                () -> 0.5
        );
    }

    @Test
    @DisplayName("Successful dispatch transitions notification to SENT, clears leases, and records attempt audit")
    void testExecute_Success_TransitionsToSent() {
        NotificationJob job = createProcessingJob(0);
        when(notificationJobRepository.findById(notificationId)).thenReturn(Optional.of(job));
        when(emailAdapter.dispatchAttempt(job)).thenReturn(NotificationAttemptResult.success(150));

        NotificationAttemptResult result = executionService.executeClaimedNotification(notificationId, claimToken);

        assertThat(result.isSuccess()).isTrue();
        assertThat(job.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(job.getAttempts()).isEqualTo(1);
        assertThat(job.getNextAttemptAt()).isNull();
        assertThat(job.getClaimToken()).isNull();

        ArgumentCaptor<NotificationAttempt> attemptCaptor = ArgumentCaptor.forClass(NotificationAttempt.class);
        verify(notificationAttemptRepository).save(attemptCaptor.capture());
        NotificationAttempt savedAttempt = attemptCaptor.getValue();
        assertThat(savedAttempt.getOutcome()).isEqualTo("SUCCESS");
        assertThat(savedAttempt.getAttemptNumber()).isEqualTo(1);
        assertThat(savedAttempt.getChannel()).isEqualTo(NotificationChannel.EMAIL);

        verify(notificationJobRepository).save(job);
    }

    @Test
    @DisplayName("Transient error schedules retry with exponential backoff when below max attempts")
    void testExecute_TransientError_SchedulesRetry() {
        NotificationJob job = createProcessingJob(0);
        when(notificationJobRepository.findById(notificationId)).thenReturn(Optional.of(job));
        when(emailAdapter.dispatchAttempt(job)).thenReturn(
                NotificationAttemptResult.transientFailure("SMTP_CONNECTION_TIMEOUT", "TIMEOUT", 200)
        );

        NotificationAttemptResult result = executionService.executeClaimedNotification(notificationId, claimToken);

        assertThat(result.retryable()).isTrue();
        assertThat(job.getStatus()).isEqualTo(NotificationStatus.PENDING);
        assertThat(job.getAttempts()).isEqualTo(1);
        // baseBackoff = 10s * 2^0 = 10s
        assertThat(job.getNextAttemptAt()).isEqualTo(fixedNow.plusSeconds(10));
        assertThat(job.getClaimToken()).isNull();

        verify(notificationAttemptRepository).save(any(NotificationAttempt.class));
        verify(notificationJobRepository).save(job);
    }

    @Test
    @DisplayName("Retry ceiling exhaustion transitions notification to terminal EXHAUSTED")
    void testExecute_RetryExhaustion_TransitionsToExhausted() {
        NotificationJob job = createProcessingJob(2); // Already 2 attempts, this is 3rd
        when(notificationJobRepository.findById(notificationId)).thenReturn(Optional.of(job));
        when(emailAdapter.dispatchAttempt(job)).thenReturn(
                NotificationAttemptResult.transientFailure("SMTP_CONNECTION_TIMEOUT", "TIMEOUT", 200)
        );

        NotificationAttemptResult result = executionService.executeClaimedNotification(notificationId, claimToken);

        assertThat(job.getStatus()).isEqualTo(NotificationStatus.EXHAUSTED);
        assertThat(job.getAttempts()).isEqualTo(3);
        assertThat(job.getNextAttemptAt()).isNull();
        assertThat(job.getClaimToken()).isNull();

        verify(notificationAttemptRepository).save(any(NotificationAttempt.class));
        verify(notificationJobRepository).save(job);
    }

    @Test
    @DisplayName("Permanent failure transitions notification immediately to terminal FAILED without retry")
    void testExecute_PermanentFailure_TransitionsToFailed() {
        NotificationJob job = createProcessingJob(0);
        when(notificationJobRepository.findById(notificationId)).thenReturn(Optional.of(job));
        when(emailAdapter.dispatchAttempt(job)).thenReturn(
                NotificationAttemptResult.permanentFailure("RECIPIENT_INELIGIBLE", "INELIGIBLE", 50)
        );

        NotificationAttemptResult result = executionService.executeClaimedNotification(notificationId, claimToken);

        assertThat(result.retryable()).isFalse();
        assertThat(job.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(job.getAttempts()).isEqualTo(1);
        assertThat(job.getNextAttemptAt()).isNull();

        verify(notificationAttemptRepository).save(any(NotificationAttempt.class));
        verify(notificationJobRepository).save(job);
    }

    @Test
    @DisplayName("Worker lease token mismatch prevents dispatch execution")
    void testExecute_LeaseTokenMismatch_Skips() {
        NotificationJob job = createProcessingJob(0);
        job.setClaimToken("different-worker-token");
        when(notificationJobRepository.findById(notificationId)).thenReturn(Optional.of(job));

        NotificationAttemptResult result = executionService.executeClaimedNotification(notificationId, claimToken);

        assertThat(result.safeErrorCode()).isEqualTo("WORKER_LEASE_NOT_HELD");
        verify(emailAdapter, never()).dispatchAttempt(any());
        verify(notificationJobRepository, never()).save(any());
    }

    @Test
    @DisplayName("Exponential backoff doubles delay and caps at maxBackoffSeconds")
    void testCalculateNextAttemptAt_ExponentialBackoff() {
        retryProperties.setBaseBackoffSeconds(5);
        retryProperties.setMaxBackoffSeconds(30);

        // Attempt 1: 5 * 2^0 = 5s
        Instant t1 = executionService.calculateNextAttemptAt(1, fixedNow);
        assertThat(t1).isEqualTo(fixedNow.plusSeconds(5));

        // Attempt 2: 5 * 2^1 = 10s
        Instant t2 = executionService.calculateNextAttemptAt(2, fixedNow);
        assertThat(t2).isEqualTo(fixedNow.plusSeconds(10));

        // Attempt 3: 5 * 2^2 = 20s
        Instant t3 = executionService.calculateNextAttemptAt(3, fixedNow);
        assertThat(t3).isEqualTo(fixedNow.plusSeconds(20));

        // Attempt 4: 5 * 2^3 = 40s -> capped at 30s
        Instant t4 = executionService.calculateNextAttemptAt(4, fixedNow);
        assertThat(t4).isEqualTo(fixedNow.plusSeconds(30));
    }

    @Test
    @DisplayName("Exponential backoff prevents arithmetic overflow for extreme attempt numbers")
    void testCalculateNextAttemptAt_OverflowSafe() {
        retryProperties.setBaseBackoffSeconds(100);
        retryProperties.setMaxBackoffSeconds(1000);

        Instant tExtreme = executionService.calculateNextAttemptAt(100, fixedNow);
        assertThat(tExtreme).isEqualTo(fixedNow.plusSeconds(1000));
    }

    @Test
    @DisplayName("Stuck processing jobs beyond lease cutoff are recovered to PENDING without attempt increment")
    void testRecoverStuckProcessingJobs() {
        NotificationJob stuckJob = createProcessingJob(1);
        stuckJob.setLeaseExpiresAt(fixedNow.minusSeconds(60));

        when(notificationJobRepository.findStuckProcessingJobs(eq(NotificationStatus.PROCESSING), any(Instant.class)))
                .thenReturn(List.of(stuckJob));

        int recovered = executionService.recoverStuckProcessingJobs(Duration.ofSeconds(300));

        assertThat(recovered).isEqualTo(1);
        assertThat(stuckJob.getStatus()).isEqualTo(NotificationStatus.PENDING);
        assertThat(stuckJob.getClaimToken()).isNull();
        assertThat(stuckJob.getLeaseExpiresAt()).isNull();
        assertThat(stuckJob.getAttempts()).isEqualTo(1); // Not incremented

        verify(notificationJobRepository).save(stuckJob);
    }

    private NotificationJob createProcessingJob(int attempts) {
        NotificationJob job = new NotificationJob(
                notificationId,
                UUID.randomUUID(),
                "sub-cust-1",
                UUID.randomUUID(),
                NotificationChannel.EMAIL,
                "sub:sub-cust-1",
                "PARCEL_CREATED"
        );
        job.setStatus(NotificationStatus.PROCESSING);
        job.setAttempts(attempts);
        job.setClaimToken(claimToken);
        job.setLeaseExpiresAt(fixedNow.plusSeconds(300));
        return job;
    }
}
