package com.swiftdrop.communicationsservice.notification.service;

import com.swiftdrop.communicationsservice.inbox.ConsumedEventRepository;
import com.swiftdrop.communicationsservice.notification.NotificationJobRepository;
import com.swiftdrop.communicationsservice.notification.NotificationStatus;
import com.swiftdrop.communicationsservice.notification.delivery.NotificationSchedulerProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationRetentionServiceTest {

    private NotificationJobRepository notificationJobRepository;
    private ConsumedEventRepository consumedEventRepository;
    private NotificationSchedulerProperties schedulerProperties;
    private NotificationRetentionService retentionService;

    private Instant testNow;
    private Clock fixedClock;

    @BeforeEach
    void setUp() {
        notificationJobRepository = mock(NotificationJobRepository.class);
        consumedEventRepository = mock(ConsumedEventRepository.class);
        schedulerProperties = new NotificationSchedulerProperties();
        schedulerProperties.setRetentionDays(30);

        testNow = Instant.parse("2026-10-28T12:00:00Z");
        fixedClock = Clock.fixed(testNow, ZoneOffset.UTC);

        retentionService = new NotificationRetentionService(
                notificationJobRepository,
                consumedEventRepository,
                schedulerProperties,
                fixedClock
        );
    }

    @Test
    @DisplayName("Purge aged records deletes terminal jobs and processed events older than 30 days cutoff")
    void purgeAgedRecords_deletesTerminalRecordsOlderThanCutoff() {
        Instant expectedCutoff = testNow.minus(Duration.ofDays(30));
        List<NotificationStatus> terminalStatuses = List.of(
                NotificationStatus.SENT,
                NotificationStatus.FAILED,
                NotificationStatus.EXHAUSTED
        );

        when(notificationJobRepository.deleteTerminalNotificationsOlderThan(eq(terminalStatuses), eq(expectedCutoff)))
                .thenReturn(15);
        when(consumedEventRepository.deleteProcessedEventsOlderThan(eq(expectedCutoff)))
                .thenReturn(22);

        NotificationRetentionService.RetentionCleanupResult result = retentionService.purgeAgedRecords();

        assertThat(result.purgedNotifications()).isEqualTo(15);
        assertThat(result.purgedInboxEvents()).isEqualTo(22);
        assertThat(result.cutoff()).isEqualTo(expectedCutoff);

        verify(notificationJobRepository).deleteTerminalNotificationsOlderThan(eq(terminalStatuses), eq(expectedCutoff));
        verify(consumedEventRepository).deleteProcessedEventsOlderThan(eq(expectedCutoff));
    }

    @Test
    @DisplayName("Purge aged records with custom retention days computes appropriate cutoff")
    void purgeAgedRecords_withCustomRetentionPeriod_computesAppropriateCutoff() {
        schedulerProperties.setRetentionDays(7);
        Instant expectedCutoff = testNow.minus(Duration.ofDays(7));
        List<NotificationStatus> terminalStatuses = List.of(
                NotificationStatus.SENT,
                NotificationStatus.FAILED,
                NotificationStatus.EXHAUSTED
        );

        when(notificationJobRepository.deleteTerminalNotificationsOlderThan(eq(terminalStatuses), eq(expectedCutoff)))
                .thenReturn(5);
        when(consumedEventRepository.deleteProcessedEventsOlderThan(eq(expectedCutoff)))
                .thenReturn(8);

        NotificationRetentionService.RetentionCleanupResult result = retentionService.purgeAgedRecords();

        assertThat(result.purgedNotifications()).isEqualTo(5);
        assertThat(result.purgedInboxEvents()).isEqualTo(8);
        assertThat(result.cutoff()).isEqualTo(expectedCutoff);
    }
}
