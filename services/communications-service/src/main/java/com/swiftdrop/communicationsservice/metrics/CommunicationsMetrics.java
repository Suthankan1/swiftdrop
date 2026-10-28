package com.swiftdrop.communicationsservice.metrics;

import com.swiftdrop.communicationsservice.inbox.ConsumedEventRepository;
import com.swiftdrop.communicationsservice.notification.NotificationJobRepository;
import com.swiftdrop.communicationsservice.notification.NotificationStatus;
import com.swiftdrop.communicationsservice.webhook.WebhookDeliveryRepository;
import com.swiftdrop.communicationsservice.webhook.WebhookDeliveryStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Observability and metrics collector for Communications Service.
 *
 * Implements bounded operational metrics per Issue #158:
 * - swiftdrop.webhook.deliveries (gauge, tags: status)
 * - swiftdrop.webhook.attempts.total (counter, tags: outcome)
 * - swiftdrop.webhook.attempt.duration (timer)
 * - swiftdrop.webhook.retry.scheduled.total (counter)
 * - swiftdrop.webhook.exhausted.total (counter)
 * - swiftdrop.webhook.stuck.recovered.total (counter)
 * - swiftdrop.notification.jobs (gauge, tags: status)
 * - swiftdrop.notification.pending.age.seconds (gauge)
 * - swiftdrop.notification.materialized.total (counter, tags: channel, status)
 * - swiftdrop.kafka.consumer.processed.total (counter, tags: consumer_group, status)
 * - swiftdrop.kafka.inbox.events (gauge, tags: consumer_group)
 *
 * Strict cardinality safeguards: no UUIDs, tenant IDs, email addresses, or secrets as tags.
 */
@Component
public class CommunicationsMetrics {

    private static final Logger log = LoggerFactory.getLogger(CommunicationsMetrics.class);
    private static final long SAMPLE_INTERVAL_MS = 1000L;

    private final MeterRegistry registry;
    private final WebhookDeliveryRepository webhookDeliveryRepository;
    private final NotificationJobRepository notificationJobRepository;
    private final ConsumedEventRepository consumedEventRepository;
    private final Clock clock;

    private final Counter webhookRetryCounter;
    private final Counter webhookExhaustedCounter;
    private final Counter webhookStuckRecoveredCounter;
    private final Timer webhookAttemptTimer;

    private final ConcurrentHashMap<String, AtomicLong> cachedWebhookDeliveries = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> cachedNotificationJobs = new ConcurrentHashMap<>();
    private final AtomicLong cachedOldestNotificationAge = new AtomicLong(0);
    private final ConcurrentHashMap<String, AtomicLong> cachedInboxEvents = new ConcurrentHashMap<>();
    private volatile long lastSampleTimeMs = 0L;

    @Autowired
    public CommunicationsMetrics(
            MeterRegistry registry,
            WebhookDeliveryRepository webhookDeliveryRepository,
            NotificationJobRepository notificationJobRepository,
            ConsumedEventRepository consumedEventRepository) {
        this(registry, webhookDeliveryRepository, notificationJobRepository, consumedEventRepository, Clock.systemUTC());
    }

    public CommunicationsMetrics(
            MeterRegistry registry,
            WebhookDeliveryRepository webhookDeliveryRepository,
            NotificationJobRepository notificationJobRepository,
            ConsumedEventRepository consumedEventRepository,
            Clock clock) {
        this.registry = Objects.requireNonNull(registry, "registry cannot be null");
        this.webhookDeliveryRepository = Objects.requireNonNull(webhookDeliveryRepository, "webhookDeliveryRepository cannot be null");
        this.notificationJobRepository = Objects.requireNonNull(notificationJobRepository, "notificationJobRepository cannot be null");
        this.consumedEventRepository = Objects.requireNonNull(consumedEventRepository, "consumedEventRepository cannot be null");
        this.clock = Objects.requireNonNull(clock, "clock cannot be null");

        // 1. Initialize counters
        this.webhookRetryCounter = Counter.builder("swiftdrop.webhook.retry.scheduled.total")
                .description("Total number of webhook retry dispatches scheduled")
                .register(registry);

        this.webhookExhaustedCounter = Counter.builder("swiftdrop.webhook.exhausted.total")
                .description("Total number of webhook deliveries transitioning to terminal EXHAUSTED")
                .register(registry);

        this.webhookStuckRecoveredCounter = Counter.builder("swiftdrop.webhook.stuck.recovered.total")
                .description("Total number of stuck PROCESSING webhook deliveries recovered")
                .register(registry);

        this.webhookAttemptTimer = Timer.builder("swiftdrop.webhook.attempt.duration")
                .description("Latency distribution of outbound webhook HTTP attempts")
                .register(registry);

        // 2. Pre-register webhook delivery gauges
        for (WebhookDeliveryStatus status : WebhookDeliveryStatus.values()) {
            String statusKey = status.name().toLowerCase();
            cachedWebhookDeliveries.put(statusKey, new AtomicLong(0));
            Gauge.builder("swiftdrop.webhook.deliveries", this, m -> m.getWebhookDeliveryCount(statusKey))
                    .description("Current count of durable webhook delivery jobs by status")
                    .tag("status", statusKey)
                    .register(registry);
        }

        // 3. Pre-register notification job gauges
        for (NotificationStatus status : NotificationStatus.values()) {
            String statusKey = status.name().toLowerCase();
            cachedNotificationJobs.put(statusKey, new AtomicLong(0));
            Gauge.builder("swiftdrop.notification.jobs", this, m -> m.getNotificationJobCount(statusKey))
                    .description("Current count of durable notification jobs by status")
                    .tag("status", statusKey)
                    .register(registry);
        }

        // 4. Notification oldest pending age gauge
        Gauge.builder("swiftdrop.notification.pending.age.seconds", this, CommunicationsMetrics::getOldestPendingNotificationAge)
                .description("Age in seconds of the oldest uncompleted pending notification job")
                .baseUnit("seconds")
                .register(registry);

        // 5. Pre-register inbox gauges
        registerInboxConsumerGroupGauge("communications.notifications.v1");
        registerInboxConsumerGroupGauge("communications.webhooks.v1");
    }

    public void registerInboxConsumerGroupGauge(String consumerGroup) {
        String safeGroup = consumerGroup.toLowerCase();
        cachedInboxEvents.computeIfAbsent(safeGroup, k -> {
            AtomicLong holder = new AtomicLong(0);
            Gauge.builder("swiftdrop.kafka.inbox.events", this, m -> m.getInboxCount(safeGroup))
                    .description("Current count of durable consumed events in inbox by consumer group")
                    .tag("consumer_group", safeGroup)
                    .register(registry);
            return holder;
        });
    }

    public void recordWebhookAttempt(String outcome, long durationMs) {
        String safeOutcome = (outcome != null && !outcome.isBlank()) ? outcome.toLowerCase() : "unknown";
        registry.counter("swiftdrop.webhook.attempts.total", "outcome", safeOutcome).increment();
        webhookAttemptTimer.record(durationMs, TimeUnit.MILLISECONDS);
    }

    public void recordWebhookRetryScheduled() {
        webhookRetryCounter.increment();
    }

    public void recordWebhookExhausted() {
        webhookExhaustedCounter.increment();
    }

    public void recordWebhookStuckRecovered(int count) {
        webhookStuckRecoveredCounter.increment(count);
    }

    public void recordNotificationMaterialized(String channel, String status) {
        String safeChannel = (channel != null && !channel.isBlank()) ? channel.toLowerCase() : "email";
        String safeStatus = (status != null && !status.isBlank()) ? status.toLowerCase() : "persisted";
        registry.counter("swiftdrop.notification.materialized.total", "channel", safeChannel, "status", safeStatus).increment();
    }

    public void recordKafkaConsumerProcessed(String consumerGroup, String status) {
        String safeGroup = (consumerGroup != null && !consumerGroup.isBlank()) ? consumerGroup.toLowerCase() : "unknown";
        String safeStatus = (status != null && !status.isBlank()) ? status.toLowerCase() : "success";
        registry.counter("swiftdrop.kafka.consumer.processed.total", "consumer_group", safeGroup, "status", safeStatus).increment();
    }

    public double getWebhookDeliveryCount(String statusKey) {
        refreshSamplingIfNecessary(false);
        AtomicLong val = cachedWebhookDeliveries.get(statusKey);
        return val != null ? val.get() : 0.0;
    }

    public double getNotificationJobCount(String statusKey) {
        refreshSamplingIfNecessary(false);
        AtomicLong val = cachedNotificationJobs.get(statusKey);
        return val != null ? val.get() : 0.0;
    }

    public double getOldestPendingNotificationAge() {
        refreshSamplingIfNecessary(false);
        return cachedOldestNotificationAge.get();
    }

    public double getInboxCount(String consumerGroupKey) {
        refreshSamplingIfNecessary(false);
        AtomicLong val = cachedInboxEvents.get(consumerGroupKey);
        return val != null ? val.get() : 0.0;
    }

    public synchronized void refreshSamplingIfNecessary(boolean force) {
        long nowMs = clock.millis();
        if (!force && (nowMs - lastSampleTimeMs < SAMPLE_INTERVAL_MS)) {
            return;
        }

        try {
            // Sample webhook delivery counts
            for (WebhookDeliveryStatus status : WebhookDeliveryStatus.values()) {
                long count = webhookDeliveryRepository.countByStatus(status);
                cachedWebhookDeliveries.computeIfAbsent(status.name().toLowerCase(), k -> new AtomicLong(0)).set(count);
            }

            // Sample notification job counts
            for (NotificationStatus status : NotificationStatus.values()) {
                long count = notificationJobRepository.countByStatus(status);
                cachedNotificationJobs.computeIfAbsent(status.name().toLowerCase(), k -> new AtomicLong(0)).set(count);
            }

            // Sample oldest pending notification age
            long pendingNotifications = cachedNotificationJobs.getOrDefault("pending", new AtomicLong(0)).get();
            if (pendingNotifications > 0) {
                Optional<Instant> oldest = notificationJobRepository.findOldestCreatedAtByStatus(NotificationStatus.PENDING);
                if (oldest.isPresent()) {
                    long age = Math.max(0, Duration.between(oldest.get(), clock.instant()).toSeconds());
                    cachedOldestNotificationAge.set(age);
                } else {
                    cachedOldestNotificationAge.set(0);
                }
            } else {
                cachedOldestNotificationAge.set(0);
            }

            // Sample inbox counts
            for (String group : cachedInboxEvents.keySet()) {
                long count = consumedEventRepository.countByIdConsumerName(group);
                cachedInboxEvents.get(group).set(count);
            }

            lastSampleTimeMs = nowMs;

        } catch (Exception ex) {
            log.warn("Failed to sample communications metrics from database: {}", ex.getMessage());
        }
    }
}
