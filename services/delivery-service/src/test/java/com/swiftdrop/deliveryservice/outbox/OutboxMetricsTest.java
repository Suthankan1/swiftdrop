package com.swiftdrop.deliveryservice.outbox;

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

class OutboxMetricsTest {

    private OutboxEventRepository repository;
    private MeterRegistry registry;
    private Clock fixedClock;
    private OutboxMetrics outboxMetrics;

    @BeforeEach
    void setUp() {
        repository = Mockito.mock(OutboxEventRepository.class);
        registry = new SimpleMeterRegistry();
        Instant fixedInstant = Instant.parse("2026-10-30T12:00:00Z");
        fixedClock = Clock.fixed(fixedInstant, ZoneOffset.UTC);
        outboxMetrics = new OutboxMetrics(repository, registry, fixedClock);
    }

    @Test
    @DisplayName("Backlog gauges observe pending, failed, and dead-letter counts")
    void backlogGauges_ReflectRepositoryCounts() {
        when(repository.countByStatus(eq(OutboxStatus.PENDING))).thenReturn(15L);
        when(repository.countByStatus(eq(OutboxStatus.FAILED))).thenReturn(3L);
        when(repository.countByStatus(eq(OutboxStatus.DEAD_LETTER))).thenReturn(1L);

        double pendingGauge = registry.get("swiftdrop.outbox.pending").gauge().value();
        double failedGauge = registry.get("swiftdrop.outbox.failed").gauge().value();
        double deadLetterGauge = registry.get("swiftdrop.outbox.dead_letter").gauge().value();

        assertThat(pendingGauge).isEqualTo(15.0);
        assertThat(failedGauge).isEqualTo(3.0);
        assertThat(deadLetterGauge).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Oldest pending age calculates seconds using injected clock")
    void oldestAgeGauge_CalculatesAgeCorrectly() {
        when(repository.countByStatus(eq(OutboxStatus.PENDING))).thenReturn(1L);
        Instant thirtySecondsAgo = fixedClock.instant().minusSeconds(30);
        when(repository.findOldestCreatedAtByStatus(eq(OutboxStatus.PENDING)))
                .thenReturn(Optional.of(thirtySecondsAgo));

        double age = registry.get("swiftdrop.outbox.oldest.age.seconds").gauge().value();
        assertThat(age).isEqualTo(30.0);
    }

    @Test
    @DisplayName("Oldest pending age returns 0 when no pending events exist")
    void oldestAgeGauge_ReturnsZeroWhenEmpty() {
        when(repository.findOldestCreatedAtByStatus(eq(OutboxStatus.PENDING)))
                .thenReturn(Optional.empty());

        double age = registry.get("swiftdrop.outbox.oldest.age.seconds").gauge().value();
        assertThat(age).isEqualTo(0.0);
    }

    @Test
    @DisplayName("Publish counters record success and failure with bounded status tag")
    void publishCounters_IncrementCorrectly() {
        outboxMetrics.recordPublish(true);
        outboxMetrics.recordPublish(true);
        outboxMetrics.recordPublish(false);

        double successCount = registry.get("swiftdrop.outbox.publish.total")
                .tag("outcome", "success").counter().count();
        double failureCount = registry.get("swiftdrop.outbox.publish.total")
                .tag("outcome", "failure").counter().count();

        assertThat(successCount).isEqualTo(2.0);
        assertThat(failureCount).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Database outage in count does not crash gauge evaluation")
    void databaseOutage_ReturnsZeroGracefully() {
        when(repository.countByStatus(eq(OutboxStatus.PENDING)))
                .thenThrow(new RuntimeException("DB Connection refused"));

        double pendingGauge = registry.get("swiftdrop.outbox.pending").gauge().value();
        assertThat(pendingGauge).isEqualTo(0.0);
    }
}
