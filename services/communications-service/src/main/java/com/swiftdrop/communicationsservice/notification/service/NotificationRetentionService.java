package com.swiftdrop.communicationsservice.notification.service;

import com.swiftdrop.communicationsservice.inbox.ConsumedEventRepository;
import com.swiftdrop.communicationsservice.notification.NotificationJobRepository;
import com.swiftdrop.communicationsservice.notification.NotificationStatus;
import com.swiftdrop.communicationsservice.notification.delivery.NotificationSchedulerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Service managing bounded retention cleanup for aged terminal notifications and processed inbox receipts (Issue #117).
 *
 * Invariants:
 * 1. Never deletes unexpired PENDING or active PROCESSING notification jobs.
 * 2. Never deletes uncompleted or unprocessed consumed_events inbox receipts.
 * 3. Preserves minimal audit records and deduplication guarantees within the retention window.
 * 4. Operates in bounded transactions with deterministic clocks.
 */
@Service
public class NotificationRetentionService {

    private static final Logger log = LoggerFactory.getLogger(NotificationRetentionService.class);

    private final NotificationJobRepository notificationJobRepository;
    private final ConsumedEventRepository consumedEventRepository;
    private final NotificationSchedulerProperties schedulerProperties;
    private final Clock clock;

    public NotificationRetentionService(
            NotificationJobRepository notificationJobRepository,
            ConsumedEventRepository consumedEventRepository,
            NotificationSchedulerProperties schedulerProperties) {
        this(notificationJobRepository, consumedEventRepository, schedulerProperties, Clock.systemUTC());
    }

    @Autowired
    public NotificationRetentionService(
            NotificationJobRepository notificationJobRepository,
            ConsumedEventRepository consumedEventRepository,
            NotificationSchedulerProperties schedulerProperties,
            Clock clock) {
        this.notificationJobRepository = Objects.requireNonNull(notificationJobRepository, "notificationJobRepository cannot be null");
        this.consumedEventRepository = Objects.requireNonNull(consumedEventRepository, "consumedEventRepository cannot be null");
        this.schedulerProperties = Objects.requireNonNull(schedulerProperties, "schedulerProperties cannot be null");
        this.clock = Objects.requireNonNull(clock, "clock cannot be null");
    }

    /**
     * Purges aged terminal notification jobs and completed consumed events older than the retention cutoff.
     *
     * @return summary record of purged items
     */
    @Transactional
    public RetentionCleanupResult purgeAgedRecords() {
        int retentionDays = schedulerProperties.getRetentionDays();
        Instant cutoff = clock.instant().minus(Duration.ofDays(retentionDays));

        log.info("Running retention cleanup for records older than cutoff [{}] (retentionDays={})", cutoff, retentionDays);

        int purgedNotifications = notificationJobRepository.deleteTerminalNotificationsOlderThan(
                List.of(NotificationStatus.SENT, NotificationStatus.FAILED, NotificationStatus.EXHAUSTED),
                cutoff
        );

        int purgedInboxEvents = consumedEventRepository.deleteProcessedEventsOlderThan(cutoff);

        log.info("Retention cleanup completed [purgedNotifications={}, purgedInboxEvents={}]",
                purgedNotifications, purgedInboxEvents);

        return new RetentionCleanupResult(purgedNotifications, purgedInboxEvents, cutoff);
    }

    public record RetentionCleanupResult(int purgedNotifications, int purgedInboxEvents, Instant cutoff) {}
}
