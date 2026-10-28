package com.swiftdrop.deliveryservice.outbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Micrometer metrics collector and state gauge for the Delivery Transactional Outbox.
 *
 * Implements bounded operational metrics per Issue #158:
 * - swiftdrop.outbox.pending (gauge): Count of pending events ready for relay
 * - swiftdrop.outbox.failed (gauge): Count of failed events awaiting retry
 * - swiftdrop.outbox.dead_letter (gauge): Count of terminal dead-lettered events
 * - swiftdrop.outbox.oldest.age.seconds (gauge): Age of the oldest unacknowledged pending event
 * - swiftdrop.outbox.publish.total (counter): Total event publication attempts partitioned by outcome
 *
 * Strictly prevents high-cardinality leakage: zero eventId, parcelId, or correlationId in tags.
 * Handles transient database outages gracefully without throwing runtime exceptions to metric scrapers.
 */
@Component
public class OutboxMetrics {

    private static final Logger log = LoggerFactory.getLogger(OutboxMetrics.class);

    private final OutboxEventRepository repository;
    private final MeterRegistry registry;
    private final Clock clock;

    private final Counter successCounter;
    private final Counter failureCounter;

    private final AtomicLong cachedPending = new AtomicLong(0);
    private final AtomicLong cachedFailed = new AtomicLong(0);
    private final AtomicLong cachedDeadLetter = new AtomicLong(0);
    private final AtomicLong cachedOldestAgeSeconds = new AtomicLong(0);

    private volatile long lastSampleTimeMs = 0;
    private static final long SAMPLE_INTERVAL_MS = 3000; // Sample at most once every 3 seconds

    @Autowired
    public OutboxMetrics(OutboxEventRepository repository, MeterRegistry registry) {
        this(repository, registry, Clock.systemUTC());
    }

    public OutboxMetrics(OutboxEventRepository repository, MeterRegistry registry, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository cannot be null");
        this.registry = Objects.requireNonNull(registry, "registry cannot be null");
        this.clock = Objects.requireNonNull(clock, "clock cannot be null");

        this.successCounter = Counter.builder("swiftdrop.outbox.publish.total")
                .description("Total number of outbox events published to Kafka broker")
                .tag("outcome", "success")
                .register(registry);

        this.failureCounter = Counter.builder("swiftdrop.outbox.publish.total")
                .description("Total number of outbox events published to Kafka broker")
                .tag("outcome", "failure")
                .register(registry);

        Gauge.builder("swiftdrop.outbox.pending", this, OutboxMetrics::getPendingCount)
                .description("Current backlog of outbox events in PENDING status")
                .register(registry);

        Gauge.builder("swiftdrop.outbox.failed", this, OutboxMetrics::getFailedCount)
                .description("Current count of outbox events in retryable FAILED status")
                .register(registry);

        Gauge.builder("swiftdrop.outbox.dead_letter", this, OutboxMetrics::getDeadLetterCount)
                .description("Current count of outbox events in terminal DEAD_LETTER status")
                .register(registry);

        Gauge.builder("swiftdrop.outbox.oldest.age.seconds", this, OutboxMetrics::getOldestPendingAgeSeconds)
                .description("Age in seconds of the oldest unacknowledged pending outbox event")
                .baseUnit("seconds")
                .register(registry);
    }

    public void recordPublish(boolean success) {
        if (success) {
            successCounter.increment();
        } else {
            failureCounter.increment();
        }
        // Force refresh cache after state changes
        refreshSamplingIfNecessary(true);
    }

    public double getPendingCount() {
        refreshSamplingIfNecessary(false);
        return cachedPending.get();
    }

    public double getFailedCount() {
        refreshSamplingIfNecessary(false);
        return cachedFailed.get();
    }

    public double getDeadLetterCount() {
        refreshSamplingIfNecessary(false);
        return cachedDeadLetter.get();
    }

    public double getOldestPendingAgeSeconds() {
        refreshSamplingIfNecessary(false);
        return cachedOldestAgeSeconds.get();
    }

    public synchronized void refreshSamplingIfNecessary(boolean force) {
        long nowMs = clock.millis();
        if (!force && (nowMs - lastSampleTimeMs < SAMPLE_INTERVAL_MS)) {
            return;
        }

        try {
            long pending = repository.countByStatus(OutboxStatus.PENDING);
            long failed = repository.countByStatus(OutboxStatus.FAILED);
            long deadLetter = repository.countByStatus(OutboxStatus.DEAD_LETTER);

            cachedPending.set(pending);
            cachedFailed.set(failed);
            cachedDeadLetter.set(deadLetter);

            if (pending > 0) {
                Optional<Instant> oldest = repository.findOldestCreatedAtByStatus(OutboxStatus.PENDING);
                if (oldest.isPresent()) {
                    long age = Math.max(0, Duration.between(oldest.get(), clock.instant()).toSeconds());
                    cachedOldestAgeSeconds.set(age);
                } else {
                    cachedOldestAgeSeconds.set(0);
                }
            } else {
                cachedOldestAgeSeconds.set(0);
            }

            lastSampleTimeMs = nowMs;
        } catch (Exception ex) {
            log.debug("Transient error querying outbox backlog metrics: {}", ex.getMessage());
        }
    }
}
