package com.swiftdrop.communicationsservice.notification.delivery;

import com.swiftdrop.communicationsservice.metrics.CommunicationsMetrics;
import com.swiftdrop.communicationsservice.notification.NotificationAttempt;
import com.swiftdrop.communicationsservice.notification.NotificationAttemptRepository;
import com.swiftdrop.communicationsservice.notification.NotificationChannel;
import com.swiftdrop.communicationsservice.notification.NotificationDispatchAdapter;
import com.swiftdrop.communicationsservice.notification.NotificationJob;
import com.swiftdrop.communicationsservice.notification.NotificationJobRepository;
import com.swiftdrop.communicationsservice.notification.NotificationStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Core engine responsible for executing claimed notification deliveries, managing worker lease claims,
 * evaluating failure categories, scheduling bounded retries, and recording attempt audit history (Issue #117).
 *
 * Invariants:
 * 1. Outbound transport dispatch occurs strictly OUTSIDE database transactions.
 * 2. Attempt history and notification job lifecycle transitions occur in an atomic transactional boundary.
 * 3. Expired or stolen worker leases cannot overwrite concurrent worker state.
 * 4. Stalled processing jobs past lease timeout are safely recovered back to PENDING.
 * 5. Bounded backoff prevents arithmetic overflow and infinite retry loops.
 */
@Service
public class NotificationExecutionService {

    private static final Logger log = LoggerFactory.getLogger(NotificationExecutionService.class);

    private final NotificationJobRepository notificationJobRepository;
    private final NotificationAttemptRepository notificationAttemptRepository;
    private final Map<NotificationChannel, NotificationDispatchAdapter> dispatchAdapters;
    private final NotificationRetryProperties retryProperties;
    private final NotificationFailureClassifier failureClassifier;
    private final Clock clock;
    private final DoubleSupplier randomSupplier;

    @Autowired(required = false)
    private CommunicationsMetrics communicationsMetrics;

    public void setCommunicationsMetrics(CommunicationsMetrics communicationsMetrics) {
        this.communicationsMetrics = communicationsMetrics;
    }

    public NotificationExecutionService(
            NotificationJobRepository notificationJobRepository,
            NotificationAttemptRepository notificationAttemptRepository,
            List<NotificationDispatchAdapter> dispatchAdapters,
            NotificationRetryProperties retryProperties,
            NotificationFailureClassifier failureClassifier) {
        this(notificationJobRepository, notificationAttemptRepository, dispatchAdapters,
                retryProperties, failureClassifier, Clock.systemUTC(), ThreadLocalRandom.current()::nextDouble);
    }

    @Autowired
    public NotificationExecutionService(
            NotificationJobRepository notificationJobRepository,
            NotificationAttemptRepository notificationAttemptRepository,
            List<NotificationDispatchAdapter> dispatchAdapters,
            NotificationRetryProperties retryProperties,
            NotificationFailureClassifier failureClassifier,
            Clock clock) {
        this(notificationJobRepository, notificationAttemptRepository, dispatchAdapters,
                retryProperties, failureClassifier, clock, ThreadLocalRandom.current()::nextDouble);
    }

    public NotificationExecutionService(
            NotificationJobRepository notificationJobRepository,
            NotificationAttemptRepository notificationAttemptRepository,
            List<NotificationDispatchAdapter> dispatchAdapters,
            NotificationRetryProperties retryProperties,
            NotificationFailureClassifier failureClassifier,
            Clock clock,
            DoubleSupplier randomSupplier) {
        this.notificationJobRepository = Objects.requireNonNull(notificationJobRepository, "notificationJobRepository cannot be null");
        this.notificationAttemptRepository = Objects.requireNonNull(notificationAttemptRepository, "notificationAttemptRepository cannot be null");
        this.dispatchAdapters = dispatchAdapters != null
                ? dispatchAdapters.stream().collect(Collectors.toMap(NotificationDispatchAdapter::getChannel, Function.identity(), (a, b) -> a))
                : Map.of();
        this.retryProperties = Objects.requireNonNull(retryProperties, "retryProperties cannot be null");
        this.failureClassifier = Objects.requireNonNull(failureClassifier, "failureClassifier cannot be null");
        this.clock = clock != null ? clock : Clock.systemUTC();
        this.randomSupplier = randomSupplier != null ? randomSupplier : ThreadLocalRandom.current()::nextDouble;
    }

    /**
     * Atomically attempts to claim a notification job with an exclusive worker lease token.
     *
     * @param notificationId ID of the job to claim
     * @param claimToken unique worker token
     * @param now current time
     * @param leaseExpiresAt expiration time for this lease
     * @return true if successfully claimed, false otherwise
     */
    public boolean tryClaimJob(UUID notificationId, String claimToken, Instant now, Instant leaseExpiresAt) {
        int updated = notificationJobRepository.claimJobWithLease(
                notificationId,
                NotificationStatus.PENDING,
                NotificationStatus.PROCESSING,
                claimToken,
                now,
                leaseExpiresAt
        );
        return updated > 0;
    }

    /**
     * Executes outbound delivery for a claimed job outside DB transactions,
     * then commits attempt history and state transitions atomically.
     *
     * @param notificationId ID of the claimed notification job
     * @param claimToken the worker claim token holding the lease
     * @return attempt result outcome
     */
    public NotificationAttemptResult executeClaimedNotification(UUID notificationId, String claimToken) {
        NotificationJob job = notificationJobRepository.findById(notificationId).orElse(null);
        if (job == null) {
            log.warn("Notification job not found for execution [notificationId={}]", notificationId);
            return NotificationAttemptResult.permanentFailure("NOT_FOUND", "JOB_NOT_FOUND", 0);
        }

        // Verify lease ownership before dispatch
        if (job.getStatus() != NotificationStatus.PROCESSING || !claimToken.equals(job.getClaimToken())) {
            log.warn("Skipping execution: notification not claimed by token [notificationId={}, status={}, tokenMatch={}]",
                    notificationId, job.getStatus(), claimToken.equals(job.getClaimToken()));
            return NotificationAttemptResult.transientFailure("LEASE_LOST", "WORKER_LEASE_NOT_HELD", 0);
        }

        // 1. Perform outbound dispatch OUTSIDE database transaction
        NotificationAttemptResult result;
        NotificationDispatchAdapter adapter = dispatchAdapters.get(job.getChannel());
        if (adapter == null) {
            result = NotificationAttemptResult.permanentFailure(
                    NotificationFailureClassifier.INVALID_CHANNEL,
                    "NO_ADAPTER_FOR_CHANNEL_" + job.getChannel(),
                    0
            );
        } else {
            try {
                result = adapter.dispatchAttempt(job);
            } catch (Exception ex) {
                result = failureClassifier.classify(ex, 0);
            }
        }

        // 2. Persist attempt record and update lifecycle inside an atomic database transaction
        persistAttemptAndAdvanceJob(notificationId, claimToken, result);

        return result;
    }

    @Transactional
    public void persistAttemptAndAdvanceJob(UUID notificationId, String claimToken, NotificationAttemptResult result) {
        Instant now = clock.instant();

        NotificationJob job = notificationJobRepository.findById(notificationId).orElse(null);
        if (job == null) {
            log.error("Unable to persist attempt: notification job missing [notificationId={}]", notificationId);
            return;
        }

        // Verify lease has not been reclaimed or stolen
        if (!claimToken.equals(job.getClaimToken()) && job.getStatus() != NotificationStatus.PROCESSING) {
            log.error("Lease ownership conflict during attempt commit [notificationId={}, currentStatus={}, heldToken={}]",
                    notificationId, job.getStatus(), job.getClaimToken());
            return;
        }

        int newAttemptNumber = (job.getAttempts() != null ? job.getAttempts() : 0) + 1;
        job.setAttempts(newAttemptNumber);
        job.setLastFailureCategory(result.failureCategory());
        job.setUpdatedAt(now);
        job.setClaimToken(null);
        job.setLeaseExpiresAt(null);

        // Record attempt audit
        NotificationAttempt attempt = new NotificationAttempt(
                UUID.randomUUID(),
                notificationId,
                newAttemptNumber,
                job.getChannel(),
                result.outcome(),
                result.failureCategory(),
                result.safeErrorCode(),
                (int) result.durationMs(),
                now,
                now
        );
        notificationAttemptRepository.save(attempt);

        if (result.isSuccess()) {
            job.setStatus(NotificationStatus.SENT);
            job.setNextAttemptAt(null);
            log.info("Notification successfully delivered [notificationId={}, channel={}, attempts={}]",
                    notificationId, job.getChannel(), newAttemptNumber);

            if (communicationsMetrics != null) {
                communicationsMetrics.recordNotificationAttempt("success", result.durationMs());
            }

        } else if (result.retryable()) {
            if (newAttemptNumber >= retryProperties.getMaxAttempts()) {
                job.setStatus(NotificationStatus.EXHAUSTED);
                job.setNextAttemptAt(null);
                log.warn("Notification retry ceiling reached: job EXHAUSTED [notificationId={}, attempts={}, max={}]",
                        notificationId, newAttemptNumber, retryProperties.getMaxAttempts());

                if (communicationsMetrics != null) {
                    communicationsMetrics.recordNotificationAttempt("exhausted", result.durationMs());
                    communicationsMetrics.recordNotificationExhausted();
                }
            } else {
                job.setStatus(NotificationStatus.PENDING);
                Instant nextAttemptAt = calculateNextAttemptAt(newAttemptNumber, now);
                job.setNextAttemptAt(nextAttemptAt);
                log.info("Scheduled notification retry [notificationId={}, attempt={}/{}, nextAttemptAt={}]",
                        notificationId, newAttemptNumber, retryProperties.getMaxAttempts(), nextAttemptAt);

                if (communicationsMetrics != null) {
                    communicationsMetrics.recordNotificationAttempt("retry_scheduled", result.durationMs());
                    communicationsMetrics.recordNotificationRetryScheduled();
                }
            }
        } else {
            // Permanent or Security failure
            job.setStatus(NotificationStatus.FAILED);
            job.setNextAttemptAt(null);
            log.warn("Notification permanently failed [notificationId={}, category={}, code={}]",
                    notificationId, result.failureCategory(), result.safeErrorCode());

            if (communicationsMetrics != null) {
                communicationsMetrics.recordNotificationAttempt("failed", result.durationMs());
            }
        }

        notificationJobRepository.save(job);
    }

    /**
     * Calculates the next attempt timestamp using bounded exponential backoff with optional jitter.
     */
    public Instant calculateNextAttemptAt(int attemptNumber, Instant now) {
        long base = retryProperties.getBaseBackoffSeconds();
        long max = retryProperties.getMaxBackoffSeconds();

        // 2^(attempt - 1) with overflow protection
        int shift = Math.min(Math.max(0, attemptNumber - 1), 30);
        long multiplier = 1L << shift;

        long rawDelay;
        if (Long.MAX_VALUE / multiplier < base) {
            rawDelay = max;
        } else {
            rawDelay = Math.min(base * multiplier, max);
        }

        long finalDelay = rawDelay;
        if (retryProperties.isJitterEnabled() && retryProperties.getJitterFactor() > 0.0) {
            double range = rawDelay * retryProperties.getJitterFactor();
            double jitter = range * (2.0 * randomSupplier.getAsDouble() - 1.0);
            finalDelay = Math.max(1L, Math.min(max, (long) (rawDelay + jitter)));
        }

        return now.plusSeconds(finalDelay);
    }

    /**
     * Recovers stalled jobs left in PROCESSING status beyond the lease timeout.
     *
     * @param leaseTimeout lease duration cutoff
     * @return count of recovered jobs
     */
    @Transactional
    public int recoverStuckProcessingJobs(Duration leaseTimeout) {
        Instant now = clock.instant();
        Instant cutoff = now.minus(leaseTimeout);

        List<NotificationJob> stuckJobs = notificationJobRepository.findStuckProcessingJobs(NotificationStatus.PROCESSING, cutoff);
        if (stuckJobs.isEmpty()) {
            return 0;
        }

        for (NotificationJob job : stuckJobs) {
            job.setStatus(NotificationStatus.PENDING);
            job.setClaimToken(null);
            job.setLeaseExpiresAt(null);
            job.setNextAttemptAt(now);
            job.setUpdatedAt(now);
            notificationJobRepository.save(job);
        }

        log.info("Recovered {} stalled notification jobs back to PENDING", stuckJobs.size());
        if (communicationsMetrics != null) {
            communicationsMetrics.recordNotificationClaimsRecovered(stuckJobs.size());
        }

        return stuckJobs.size();
    }
}
