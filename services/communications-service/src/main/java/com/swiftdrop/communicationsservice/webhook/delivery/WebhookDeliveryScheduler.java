package com.swiftdrop.communicationsservice.webhook.delivery;

import com.swiftdrop.communicationsservice.webhook.WebhookDelivery;
import com.swiftdrop.communicationsservice.webhook.WebhookDeliveryRepository;
import com.swiftdrop.communicationsservice.webhook.WebhookDeliveryStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Durable scheduler component responsible for periodically polling due webhook deliveries,
 * safely leasing jobs to prevent concurrent duplicate execution, executing outbound dispatches,
 * and recovering stalled/crashed processing jobs.
 *
 * Invariants:
 * 1. Safe leasing: each candidate job is claimed via atomic database update before execution.
 * 2. Concurrency defense: concurrent workers attempting to claim the same job will observe
 *    an update count of 0 and skip execution, preventing duplicate attempts.
 * 3. Durable state: pending retries survive process restarts because next execution times
 *    are durably stored in PostgreSQL.
 * 4. Stalled recovery: jobs left in PROCESSING beyond lease timeout are safely returned to PENDING.
 * 5. Terminal status: EXHAUSTED, SUCCESS, and FAILED deliveries are never rescheduled.
 */
@Component
public class WebhookDeliveryScheduler {

    private static final Logger log = LoggerFactory.getLogger(WebhookDeliveryScheduler.class);

    private final WebhookDeliveryRepository webhookDeliveryRepository;
    private final WebhookDeliveryExecutionService executionService;
    private final WebhookSchedulerProperties schedulerProperties;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean(true);

    public WebhookDeliveryScheduler(
            WebhookDeliveryRepository webhookDeliveryRepository,
            WebhookDeliveryExecutionService executionService,
            WebhookSchedulerProperties schedulerProperties) {
        this(webhookDeliveryRepository, executionService, schedulerProperties, Clock.systemUTC());
    }

    public WebhookDeliveryScheduler(
            WebhookDeliveryRepository webhookDeliveryRepository,
            WebhookDeliveryExecutionService executionService,
            WebhookSchedulerProperties schedulerProperties,
            Clock clock) {
        this.webhookDeliveryRepository = Objects.requireNonNull(webhookDeliveryRepository, "webhookDeliveryRepository cannot be null");
        this.executionService = Objects.requireNonNull(executionService, "executionService cannot be null");
        this.schedulerProperties = Objects.requireNonNull(schedulerProperties, "schedulerProperties cannot be null");
        this.clock = Objects.requireNonNull(clock, "clock cannot be null");
    }

    /**
     * Polls due deliveries and leases/executes them sequentially in controlled batches.
     * Safely leases each candidate job so concurrent workers cannot schedule uncontrolled duplicates.
     *
     * @return number of deliveries successfully claimed and dispatched in this run.
     */
    @Scheduled(fixedDelayString = "${swiftdrop.webhooks.scheduler.poll-interval-ms:5000}")
    public int schedulePendingDeliveries() {
        if (!running.get() || !schedulerProperties.isEnabled()) {
            return 0;
        }

        Instant now = clock.instant();
        Instant leaseCutoff = now.minusSeconds(schedulerProperties.getLeaseTimeoutSeconds());
        Pageable pageable = PageRequest.of(0, schedulerProperties.getBatchSize());

        List<WebhookDelivery> dueDeliveries = webhookDeliveryRepository
                .findDueDeliveries(WebhookDeliveryStatus.PENDING, now, pageable);

        if (dueDeliveries.isEmpty()) {
            return 0;
        }

        log.debug("Discovered {} due webhook deliveries for processing", dueDeliveries.size());
        int executedCount = 0;

        for (WebhookDelivery delivery : dueDeliveries) {
            if (!running.get()) {
                break;
            }

            try {
                // Safely lease due job using atomic database query
                boolean claimed = executionService.tryClaimDelivery(delivery.getDeliveryId(), leaseCutoff);
                if (!claimed) {
                    log.debug("Delivery job already claimed by concurrent worker or no longer due [deliveryId={}]",
                            delivery.getDeliveryId());
                    continue;
                }

                log.info("Leased due webhook delivery [deliveryId={}, attemptCount={}]",
                        delivery.getDeliveryId(), delivery.getAttemptCount());

                // Execute outbound HTTP dispatch and persist outcome
                executionService.executeDelivery(delivery);
                executedCount++;

            } catch (Exception ex) {
                log.error("Unhandled error executing scheduled webhook delivery [deliveryId={}]",
                        delivery.getDeliveryId(), ex);
            }
        }

        return executedCount;
    }

    /**
     * Recovers jobs that were left in PROCESSING status beyond the lease timeout
     * due to process crashes or network partition.
     *
     * @return number of stalled jobs recovered.
     */
    @Scheduled(fixedDelayString = "${swiftdrop.webhooks.scheduler.recovery-interval-ms:60000}")
    public int recoverStalledDeliveries() {
        if (!running.get() || !schedulerProperties.isEnabled()) {
            return 0;
        }

        try {
            Duration leaseTimeout = Duration.ofSeconds(schedulerProperties.getLeaseTimeoutSeconds());
            int recovered = executionService.recoverStuckProcessingJobs(leaseTimeout);
            if (recovered > 0) {
                log.info("Recovered {} stalled webhook delivery jobs past lease timeout", recovered);
            }
            return recovered;
        } catch (Exception ex) {
            log.error("Failed to recover stalled webhook delivery jobs", ex);
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
