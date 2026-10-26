package com.swiftdrop.deliveryservice.outbox;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KafkaOutboxRelayTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private OutboxRelayProperties properties;
    private KafkaOutboxRelay relay;

    private OutboxEvent sampleEvent;
    private final UUID eventId = UUID.randomUUID();
    private final UUID parcelId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        properties = new OutboxRelayProperties();
        properties.setEnabled(true);
        properties.setBatchSize(10);
        properties.setInitialBackoffSeconds(1L);
        properties.setBackoffMultiplier(2.0);
        properties.setMaxBackoffSeconds(60L);
        properties.setMaxAttempts(5);
        properties.setClaimTimeoutSeconds(30L);

        relay = new KafkaOutboxRelay(outboxEventRepository, properties, kafkaTemplate);

        sampleEvent = new OutboxEvent(
                eventId,
                "PARCEL",
                parcelId,
                2L,
                "parcel.status.changed.v1",
                "1.0.0",
                "{\"eventId\":\"" + eventId + "\"}",
                "parcel.lifecycle.v1",
                parcelId.toString()
        );
    }

    @Test
    @DisplayName("1. Happy path: broker ACK marks outbox event as PUBLISHED with acknowledgedAt")
    void testDispatchSuccessMarksPublished() {
        when(outboxEventRepository.claimNextBatch(any(Instant.class), eq(5), eq(10)))
                .thenReturn(List.of(sampleEvent));
        when(outboxEventRepository.saveAll(anyList()))
                .thenAnswer(inv -> inv.getArgument(0));
        when(outboxEventRepository.findById(eventId))
                .thenReturn(Optional.of(sampleEvent));

        RecordMetadata metadata = new RecordMetadata(
                new TopicPartition("parcel.lifecycle.v1", 0), 0, 0, System.currentTimeMillis(), 0, 0);
        ProducerRecord<String, String> producerRecord = new ProducerRecord<>("parcel.lifecycle.v1", parcelId.toString(), sampleEvent.getPayload());
        SendResult<String, String> sendResult = new SendResult<>(producerRecord, metadata);

        CompletableFuture<SendResult<String, String>> future = CompletableFuture.completedFuture(sendResult);
        when(kafkaTemplate.send(eq("parcel.lifecycle.v1"), eq(parcelId.toString()), any(String.class)))
                .thenReturn(future);

        int processed = relay.processBatch();

        assertThat(processed).isEqualTo(1);
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(captor.capture());

        OutboxEvent publishedEvent = captor.getValue();
        assertThat(publishedEvent.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(publishedEvent.getAcknowledgedAt()).isNotNull();
        assertThat(publishedEvent.getLastError()).isNull();
        assertThat(publishedEvent.getClaimExpiresAt()).isNull();
        assertThat(publishedEvent.getClaimedBy()).isNull();
    }

    @Test
    @DisplayName("2. Broker unavailable: records failure, increments attemptCount, and applies exponential backoff")
    void testBrokerFailureAppliesExponentialBackoff() {
        when(outboxEventRepository.claimNextBatch(any(Instant.class), eq(5), eq(10)))
                .thenReturn(List.of(sampleEvent));
        when(outboxEventRepository.saveAll(anyList()))
                .thenAnswer(inv -> inv.getArgument(0));
        when(outboxEventRepository.findById(eventId))
                .thenReturn(Optional.of(sampleEvent));

        CompletableFuture<SendResult<String, String>> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("Kafka broker connection timeout"));

        when(kafkaTemplate.send(eq("parcel.lifecycle.v1"), eq(parcelId.toString()), any(String.class)))
                .thenReturn(failedFuture);

        int processed = relay.processBatch();

        assertThat(processed).isEqualTo(0);
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(captor.capture());

        OutboxEvent failedEvent = captor.getValue();
        assertThat(failedEvent.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(failedEvent.getAttemptCount()).isEqualTo(1);
        assertThat(failedEvent.getLastError()).contains("Kafka broker connection timeout");
        assertThat(failedEvent.getNextAttemptAt()).isNotNull();
        assertThat(failedEvent.getClaimExpiresAt()).isNull();
        assertThat(failedEvent.getClaimedBy()).isNull();
    }

    @Test
    @DisplayName("3. Poison event: transitions to DEAD_LETTER after exceeding maxAttempts without infinite retry")
    void testPoisonEventTransitionsToDeadLetter() {
        sampleEvent.setAttemptCount(4); // Will become 5 during claimEvents

        when(outboxEventRepository.claimNextBatch(any(Instant.class), eq(5), eq(10)))
                .thenReturn(List.of(sampleEvent));
        when(outboxEventRepository.saveAll(anyList()))
                .thenAnswer(inv -> inv.getArgument(0));
        when(outboxEventRepository.findById(eventId))
                .thenReturn(Optional.of(sampleEvent));

        CompletableFuture<SendResult<String, String>> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("Schema validation rejected on broker"));

        when(kafkaTemplate.send(eq("parcel.lifecycle.v1"), eq(parcelId.toString()), any(String.class)))
                .thenReturn(failedFuture);

        int processed = relay.processBatch();

        assertThat(processed).isEqualTo(0);
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(captor.capture());

        OutboxEvent deadLetterEvent = captor.getValue();
        assertThat(deadLetterEvent.getStatus()).isEqualTo(OutboxStatus.DEAD_LETTER);
        assertThat(deadLetterEvent.getAttemptCount()).isEqualTo(5);
        assertThat(deadLetterEvent.getLastError()).contains("Schema validation rejected on broker");
    }

    @Test
    @DisplayName("4. Relay disabled: does not poll repository when disabled via configuration")
    void testRelayDisabledSkipsPolling() {
        properties.setEnabled(false);

        relay.scheduleRelayCycle();

        verify(outboxEventRepository, never()).claimNextBatch(any(), anyInt(), anyInt());
        verify(kafkaTemplate, never()).send(any(), any(), any());
    }

    @Test
    @DisplayName("5. Safe empty poll: handles empty batch without error")
    void testEmptyBatch() {
        when(outboxEventRepository.claimNextBatch(any(Instant.class), eq(5), eq(10)))
                .thenReturn(Collections.emptyList());

        int processed = relay.processBatch();

        assertThat(processed).isEqualTo(0);
        verify(kafkaTemplate, never()).send(any(), any(), any());
        verify(outboxEventRepository, never()).save(any());
    }
}
