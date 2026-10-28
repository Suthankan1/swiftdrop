package com.swiftdrop.communicationsservice.notification.delivery;

import com.swiftdrop.communicationsservice.notification.NotificationJob;
import com.swiftdrop.communicationsservice.notification.NotificationJobRepository;
import com.swiftdrop.communicationsservice.notification.NotificationStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Background scheduler polling due notification jobs, safely claiming exclusive worker leases
 * to prevent duplicate dispatches across multiple horizontally scaled replicas (Issue #117).
 *
 * Invariants:
 * 1. Safe leasing: each candidate job is claimed via atomic database update before execution.
 * 2. Concurrency defense: concurrent workers attempting to claim the same job will observe
 *    an update count of 0 and skip execution, preventing duplicate attempts.
 * 3. Durable state: pending retries survive process restarts because next execution times
 *    are durably stored in PostgreSQL.
 * 4. Stalled recovery: jobs left in PROCESSING beyond lease timeout are safely returned to PENDING.
 * 5. Terminal status: EXHAUSTED, SENT, and FAILED notifications are never rescheduled.
 */
@Component
public class NotificationDeliveryScheduler {

    private static final Logger log = LoggerFactory.getLogger(NotificationDeliveryScheduler.class);

    private final NotificationJobRepository notificationJobRepository;
    private final NotificationExecutionService executionService;
    private final NotificationSchedulerProperties schedulerProperties;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean(true);

    public NotificationDeliveryScheduler(
            NotificationJobRepository notificationJobRepository,
            NotificationExecutionService executionService,
            NotificationSchedulerProperties schedulerProperties) {
        this(notificationJobRepository, executionService, schedulerProperties, Clock.systemUTC());
    }

    @Autowired
    public NotificationDeliveryScheduler(
            NotificationJobRepository notificationJobRepository,
            NotificationExecutionService executionService,
            NotificationSchedulerProperties schedulerProperties,
            Clock clock) {
        this.notificationJobRepository = Objects.requireNonNull(notificationJobRepository, "notificationJobRepository cannot be null");
        this.executionService = Objects.requireNonNull(executionService, "executionService cannot be null");
        this.schedulerProperties = Objects.requireNonNull(schedulerProperties, "schedulerProperties cannot be null");
        this.clock = Objects.requireNonNull(clock, "clock cannot be null");
    }

    /**
     * Polls due notification jobs and leases/executes them sequentially in controlled batches.
     *
     * @return count of successfully claimed and dispatched jobs in this run
     */
    @Scheduled(fixedDelayString = "${swiftdrop.notifications.scheduler.poll-interval-ms:5000}")
    public int schedulePendingNotifications() {
        if (!running.get() || !schedulerProperties.isEnabled()) {
            return 0;
        }

        Instant now = clock.instant();
        Pageable pageable = PageRequest.of(0, schedulerProperties.getBatchSize());

        List<NotificationJob> dueJobs = notificationJobRepository.findDueNotifications(
                NotificationStatus.PENDING,
                now,
                pageable
        );

        if (dueJobs.isEmpty()) {
            return 0;
        }

        log.debug("Discovered {} due notification jobs for processing", dueJobs.size());
        int executedCount = 0;

        for (NotificationJob job : dueJobs) {
            if (!running.get()) {
                break;
            }

            try {
                String claimToken = UUID.randomUUID().toString();
                Instant leaseExpiresAt = now.plusSeconds(schedulerProperties.getLeaseTimeoutSeconds());

                boolean claimed = executionService.tryClaimJob(
                        job.getNotificationId(),
                        claimToken,
                        now,
                        leaseExpiresAt
                );

                if (!claimed) {
                    log.debug("Job already claimed by concurrent replica or not due [notificationId={}]",
                            job.getNotificationId());
                    continue;
                }

                log.info("Leased due notification job [notificationId={}, attempts={}]",
                        job.getNotificationId(), job.getAttempts());

                executionService.executeClaimedNotification(job.getNotificationId(), claimToken);
                executedCount++;

            } catch (Exception ex) {
                log.error("Unhandled error executing scheduled notification job [notificationId={}]",
                        job.getNotificationId(), ex);
            }
        }

        return executedCount;
    }

    /**
     * Recovers jobs that were left in PROCESSING status beyond the lease timeout.
     *
     * @return count of recovered jobs
     */
    @Scheduled(fixedDelayString = "${swiftdrop.notifications.scheduler.recovery-interval-ms:60000}")
    public int recoverStalledNotifications() {
        if (!running.get() || !schedulerProperties.isEnabled()) {
            return 0;
        }

        try {
            Duration leaseTimeout = Duration.ofSeconds(schedulerProperties.getLeaseTimeoutSeconds());
            int recovered = executionService.recoverStuckProcessingJobs(leaseTimeout);
            if (recovered > 0) {
                log.info("Recovered {} stalled notification jobs past lease timeout", recovered);
            }
            return recovered;
        } catch (Exception ex) {
            log.error("Failed to recover stalled notification jobs", ex);
            return 0;
        }
    }

    public void stop() {
        running.set(false);
    }

    public void start() {
        running.set(true);
    }

    public boolean isRunning() {
        return running.get();
    }
}
