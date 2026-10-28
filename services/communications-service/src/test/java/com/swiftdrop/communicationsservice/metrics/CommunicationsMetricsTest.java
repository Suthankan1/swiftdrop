package com.swiftdrop.communicationsservice.metrics;

import com.swiftdrop.communicationsservice.inbox.ConsumedEventRepository;
import com.swiftdrop.communicationsservice.notification.NotificationJobRepository;
import com.swiftdrop.communicationsservice.notification.NotificationStatus;
import com.swiftdrop.communicationsservice.webhook.WebhookDeliveryRepository;
import com.swiftdrop.communicationsservice.webhook.WebhookDeliveryStatus;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

class CommunicationsMetricsTest {

    private MeterRegistry registry;
    private WebhookDeliveryRepository webhookDeliveryRepository;
    private NotificationJobRepository notificationJobRepository;
    private ConsumedEventRepository consumedEventRepository;
    private Clock fixedClock;
    private CommunicationsMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        webhookDeliveryRepository = Mockito.mock(WebhookDeliveryRepository.class);
        notificationJobRepository = Mockito.mock(NotificationJobRepository.class);
        consumedEventRepository = Mockito.mock(ConsumedEventRepository.class);
        Instant fixedInstant = Instant.parse("2026-10-30T15:00:00Z");
        fixedClock = Clock.fixed(fixedInstant, ZoneOffset.UTC);

        metrics = new CommunicationsMetrics(
                registry,
                webhookDeliveryRepository,
                notificationJobRepository,
                consumedEventRepository,
                fixedClock
        );
    }

    @Test
    @DisplayName("Webhook deliveries gauge reflects repository counts by status")
    void webhookDeliveriesGauge_ReflectsRepositoryCounts() {
        when(webhookDeliveryRepository.countByStatus(eq(WebhookDeliveryStatus.PENDING))).thenReturn(10L);
        when(webhookDeliveryRepository.countByStatus(eq(WebhookDeliveryStatus.FAILED))).thenReturn(2L);
        when(webhookDeliveryRepository.countByStatus(eq(WebhookDeliveryStatus.EXHAUSTED))).thenReturn(1L);

        double pending = registry.get("swiftdrop.webhook.deliveries").tag("status", "pending").gauge().value();
        double failed = registry.get("swiftdrop.webhook.deliveries").tag("status", "failed").gauge().value();
        double exhausted = registry.get("swiftdrop.webhook.deliveries").tag("status", "exhausted").gauge().value();

        assertThat(pending).isEqualTo(10.0);
        assertThat(failed).isEqualTo(2.0);
        assertThat(exhausted).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Notification jobs gauge and oldest age calculate correctly")
    void notificationJobGauges_CalculateCorrectly() {
        when(notificationJobRepository.countByStatus(eq(NotificationStatus.PENDING))).thenReturn(5L);
        Instant fortySecondsAgo = fixedClock.instant().minusSeconds(40);
        when(notificationJobRepository.findOldestCreatedAtByStatus(eq(NotificationStatus.PENDING)))
                .thenReturn(Optional.of(fortySecondsAgo));

        double pendingJobs = registry.get("swiftdrop.notification.jobs").tag("status", "pending").gauge().value();
        double oldestAge = registry.get("swiftdrop.notification.pending.age.seconds").gauge().value();

        assertThat(pendingJobs).isEqualTo(5.0);
        assertThat(oldestAge).isEqualTo(40.0);
    }

    @Test
    @DisplayName("Kafka inbox events gauge observes count by consumer group")
    void kafkaInboxEventsGauge_ObservesCount() {
        when(consumedEventRepository.countByIdConsumerName(eq("communications.notifications.v1"))).thenReturn(42L);

        double count = registry.get("swiftdrop.kafka.inbox.events")
                .tag("consumer_group", "communications.notifications.v1").gauge().value();
        assertThat(count).isEqualTo(42.0);
    }

    @Test
    @DisplayName("Webhook operational counters and timer increment accurately with bounded tags")
    void webhookOperationalCounters_IncrementAccurately() {
        metrics.recordWebhookAttempt("CLIENT_ERROR", 150);
        metrics.recordWebhookAttempt("SERVER_ERROR", 250);
        metrics.recordWebhookRetryScheduled();
        metrics.recordWebhookExhausted();
        metrics.recordWebhookStuckRecovered(3);

        double clientErrorAttempts = registry.get("swiftdrop.webhook.attempts.total")
                .tag("outcome", "client_error").counter().count();
        double serverErrorAttempts = registry.get("swiftdrop.webhook.attempts.total")
                .tag("outcome", "server_error").counter().count();
        double retriesScheduled = registry.get("swiftdrop.webhook.retry.scheduled.total").counter().count();
        double exhausted = registry.get("swiftdrop.webhook.exhausted.total").counter().count();
        double recovered = registry.get("swiftdrop.webhook.stuck.recovered.total").counter().count();
        long timerCount = registry.get("swiftdrop.webhook.attempt.duration").timer().count();

        assertThat(clientErrorAttempts).isEqualTo(1.0);
        assertThat(serverErrorAttempts).isEqualTo(1.0);
        assertThat(retriesScheduled).isEqualTo(1.0);
        assertThat(exhausted).isEqualTo(1.0);
        assertThat(recovered).isEqualTo(3.0);
        assertThat(timerCount).isEqualTo(2L);
    }

    @Test
    @DisplayName("Notification materialization and Kafka consumer processed counters record bounded metrics")
    void notificationAndConsumerCounters_RecordCorrectly() {
        metrics.recordNotificationMaterialized("EMAIL", "PENDING");
        metrics.recordKafkaConsumerProcessed("communications.notifications.v1", "success");
        metrics.recordKafkaConsumerProcessed("communications.notifications.v1", "failure");

        double materialized = registry.get("swiftdrop.notification.materialized.total")
                .tag("channel", "email").tag("status", "pending").counter().count();
        double consumerSuccess = registry.get("swiftdrop.kafka.consumer.processed.total")
                .tag("consumer_group", "communications.notifications.v1").tag("status", "success").counter().count();
        double consumerFailure = registry.get("swiftdrop.kafka.consumer.processed.total")
                .tag("consumer_group", "communications.notifications.v1").tag("status", "failure").counter().count();

        assertThat(materialized).isEqualTo(1.0);
        assertThat(consumerSuccess).isEqualTo(1.0);
        assertThat(consumerFailure).isEqualTo(1.0);
    }
}
