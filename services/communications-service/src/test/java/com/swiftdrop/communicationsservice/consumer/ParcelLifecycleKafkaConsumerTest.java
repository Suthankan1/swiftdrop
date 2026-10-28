package com.swiftdrop.communicationsservice.consumer;

import com.swiftdrop.communicationsservice.client.DeliveryServiceUnavailableException;
import com.swiftdrop.communicationsservice.consumer.event.ParcelStatusChangedEvent;
import com.swiftdrop.communicationsservice.consumer.event.ParcelStatusChangedPayload;
import com.swiftdrop.communicationsservice.consumer.quarantine.PoisonEventQuarantine;
import com.swiftdrop.communicationsservice.consumer.quarantine.PoisonEventQuarantineService;
import com.swiftdrop.communicationsservice.metrics.CommunicationsMetrics;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ParcelLifecycleKafkaConsumerTest {

    @Mock
    private ParcelLifecycleEventParser eventParser;

    @Mock
    private NotificationSchedulingService schedulingService;

    @Mock
    private Acknowledgment acknowledgment;

    @Mock
    private PoisonEventQuarantineService quarantineService;

    @Mock
    private CommunicationsMetrics communicationsMetrics;

    private ParcelLifecycleKafkaConsumer consumer;

    private final UUID eventId = UUID.randomUUID();
    private final UUID parcelId = UUID.randomUUID();
    private ParcelStatusChangedEvent sampleEvent;

    @BeforeEach
    void setUp() {
        consumer = new ParcelLifecycleKafkaConsumer(eventParser, schedulingService);

        ParcelStatusChangedPayload payload = new ParcelStatusChangedPayload(
                parcelId,
                "SWD-20261026-A1B2",
                "OUT_FOR_DELIVERY",
                "DELIVERED",
                null,
                null,
                "courier-1",
                "Colombo-03"
        );

        sampleEvent = new ParcelStatusChangedEvent(
                eventId,
                "parcel.status.changed.v1",
                "1.0.0",
                parcelId,
                "PARCEL",
                3L,
                Instant.now(),
                "corr-1",
                "delivery-operations",
                payload
        );
    }

    @Test
    @DisplayName("Successfully process lifecycle message and acknowledge Kafka offset")
    void testOnMessage_Success_AcknowledgesOffset() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                "parcel.lifecycle.v1", 0, 100L, parcelId.toString(), "{\"eventId\":\"" + eventId + "\"}"
        );

        when(eventParser.parseAndValidate(record.key(), record.value())).thenReturn(sampleEvent);
        when(schedulingService.processLifecycleEvent(sampleEvent)).thenReturn(SchedulingResult.duplicate(eventId));

        consumer.onMessage(record, acknowledgment);

        verify(eventParser).parseAndValidate(record.key(), record.value());
        verify(schedulingService).processLifecycleEvent(sampleEvent);
        verify(acknowledgment).acknowledge();
    }

    @Test
    @DisplayName("Parser error fails fast and strictly avoids Kafka offset acknowledgement")
    void testOnMessage_ParserFails_DoesNotAcknowledge() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                "parcel.lifecycle.v1", 0, 100L, parcelId.toString(), "invalid-json"
        );

        when(eventParser.parseAndValidate(record.key(), record.value()))
                .thenThrow(new EventValidationException("Invalid JSON"));

        assertThatThrownBy(() -> consumer.onMessage(record, acknowledgment))
                .isInstanceOf(EventValidationException.class);

        verify(schedulingService, never()).processLifecycleEvent(any());
        verify(acknowledgment, never()).acknowledge();
    }

    @Test
    @DisplayName("Scheduling or persistence failure avoids Kafka offset acknowledgement to allow redelivery")
    void testOnMessage_SchedulingFails_DoesNotAcknowledge() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                "parcel.lifecycle.v1", 0, 100L, parcelId.toString(), "{\"eventId\":\"" + eventId + "\"}"
        );

        when(eventParser.parseAndValidate(record.key(), record.value())).thenReturn(sampleEvent);
        when(schedulingService.processLifecycleEvent(sampleEvent))
                .thenThrow(new DeliveryServiceUnavailableException("Delivery service down"));

        assertThatThrownBy(() -> consumer.onMessage(record, acknowledgment))
                .isInstanceOf(DeliveryServiceUnavailableException.class);

        verify(acknowledgment, never()).acknowledge();
    }

    @Test
    @DisplayName("Null acknowledgment handle is handled safely without throwing NullPointerException")
    void testOnMessage_NullAcknowledgment_DoesNotThrow() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                "parcel.lifecycle.v1", 0, 100L, parcelId.toString(), "{\"eventId\":\"" + eventId + "\"}"
        );

        when(eventParser.parseAndValidate(record.key(), record.value())).thenReturn(sampleEvent);
        when(schedulingService.processLifecycleEvent(sampleEvent)).thenReturn(SchedulingResult.duplicate(eventId));

        consumer.onMessage(record, null);

        verify(eventParser).parseAndValidate(record.key(), record.value());
        verify(schedulingService).processLifecycleEvent(sampleEvent);
    }

    @Test
    @DisplayName("Parser error with quarantineService enabled persists quarantine and acknowledges offset")
    void testOnMessage_ParserFails_WithQuarantineService_QuarantinesAndAcknowledgesOffset() {
        ParcelLifecycleKafkaConsumer consumerWithQuarantine = new ParcelLifecycleKafkaConsumer(
                eventParser, schedulingService, quarantineService
        );
        consumerWithQuarantine.setCommunicationsMetrics(communicationsMetrics);

        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                "parcel.lifecycle.v1", 2, 500L, parcelId.toString(), "invalid-json"
        );

        EventValidationException validationException = new EventValidationException("Malformed JSON envelope");
        when(eventParser.parseAndValidate(record.key(), record.value()))
                .thenThrow(validationException);

        when(quarantineService.quarantine(
                eq("communications.notifications.v1"),
                eq("parcel.lifecycle.v1"),
                eq(2),
                eq(500L),
                eq(parcelId.toString()),
                eq("invalid-json"),
                eq(validationException)
        )).thenReturn(new PoisonEventQuarantine());

        consumerWithQuarantine.onMessage(record, acknowledgment);

        verify(quarantineService).quarantine(
                eq("communications.notifications.v1"),
                eq("parcel.lifecycle.v1"),
                eq(2),
                eq(500L),
                eq(parcelId.toString()),
                eq("invalid-json"),
                eq(validationException)
        );
        verify(acknowledgment).acknowledge();
        verify(communicationsMetrics).recordKafkaConsumerProcessed("communications.notifications.v1", "quarantined");
    }

    @Test
    @DisplayName("Parser error when quarantine persistence fails avoids offset ack and rethrows")
    void testOnMessage_ParserFails_WhenQuarantineThrows_DoesNotAcknowledgeAndFailsClosed() {
        ParcelLifecycleKafkaConsumer consumerWithQuarantine = new ParcelLifecycleKafkaConsumer(
                eventParser, schedulingService, quarantineService
        );
        consumerWithQuarantine.setCommunicationsMetrics(communicationsMetrics);

        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                "parcel.lifecycle.v1", 2, 500L, parcelId.toString(), "invalid-json"
        );

        EventValidationException validationException = new EventValidationException("Malformed JSON envelope");
        when(eventParser.parseAndValidate(record.key(), record.value()))
                .thenThrow(validationException);

        when(quarantineService.quarantine(any(), any(), anyInt(), anyLong(), any(), any(), any()))
                .thenThrow(new RuntimeException("Database down"));

        assertThatThrownBy(() -> consumerWithQuarantine.onMessage(record, acknowledgment))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Database down");

        verify(acknowledgment, never()).acknowledge();
        verify(communicationsMetrics).recordPoisonQuarantineFailed();
    }
}
