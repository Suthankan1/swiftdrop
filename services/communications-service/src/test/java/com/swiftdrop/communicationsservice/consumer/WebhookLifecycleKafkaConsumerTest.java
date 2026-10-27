package com.swiftdrop.communicationsservice.consumer;

import com.swiftdrop.communicationsservice.consumer.event.ParcelStatusChangedEvent;
import com.swiftdrop.communicationsservice.consumer.event.ParcelStatusChangedPayload;
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
class WebhookLifecycleKafkaConsumerTest {

    @Mock
    private ParcelLifecycleEventParser eventParser;

    @Mock
    private WebhookJobMaterializationService materializationService;

    @Mock
    private Acknowledgment acknowledgment;

    private WebhookLifecycleKafkaConsumer consumer;

    private final UUID eventId = UUID.randomUUID();
    private final UUID parcelId = UUID.randomUUID();
    private final UUID merchantId = UUID.randomUUID();
    private ParcelStatusChangedEvent sampleEvent;

    @BeforeEach
    void setUp() {
        consumer = new WebhookLifecycleKafkaConsumer(eventParser, materializationService);

        ParcelStatusChangedPayload payload = new ParcelStatusChangedPayload(
                parcelId,
                "SWD-20261026-A1B2",
                "PICKED_UP",
                "IN_TRANSIT",
                null,
                merchantId,
                "courier-1",
                "Colombo-03"
        );

        sampleEvent = new ParcelStatusChangedEvent(
                eventId,
                "parcel.status.changed.v1",
                "1.0.0",
                parcelId,
                "PARCEL",
                2L,
                Instant.now(),
                "corr-1",
                "delivery-operations",
                payload
        );
    }

    @Test
    @DisplayName("Successfully process lifecycle message and acknowledge Kafka offset strictly after persistence")
    void testOnMessage_Success_AcknowledgesOffset() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                "parcel.lifecycle.v1", 0, 100L, parcelId.toString(), "{\"eventId\":\"" + eventId + "\"}"
        );

        when(eventParser.parseAndValidate(record.key(), record.value())).thenReturn(sampleEvent);
        when(materializationService.processLifecycleEvent(sampleEvent, record.value()))
                .thenReturn(WebhookMaterializationResult.duplicate(eventId));

        consumer.onMessage(record, acknowledgment);

        verify(eventParser).parseAndValidate(record.key(), record.value());
        verify(materializationService).processLifecycleEvent(sampleEvent, record.value());
        verify(acknowledgment).acknowledge();
    }

    @Test
    @DisplayName("Parser error fails fast and strictly avoids Kafka offset acknowledgement")
    void testOnMessage_ParserFails_DoesNotAcknowledge() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                "parcel.lifecycle.v1", 0, 100L, parcelId.toString(), "invalid-json"
        );

        when(eventParser.parseAndValidate(record.key(), record.value()))
                .thenThrow(new EventValidationException("Malformed JSON payload"));

        assertThatThrownBy(() -> consumer.onMessage(record, acknowledgment))
                .isInstanceOf(EventValidationException.class);

        verify(materializationService, never()).processLifecycleEvent(any(), any());
        verify(acknowledgment, never()).acknowledge();
    }

    @Test
    @DisplayName("Materialization failure avoids Kafka offset acknowledgement to allow broker redelivery")
    void testOnMessage_MaterializationFails_DoesNotAcknowledge() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                "parcel.lifecycle.v1", 0, 100L, parcelId.toString(), "{\"eventId\":\"" + eventId + "\"}"
        );

        when(eventParser.parseAndValidate(record.key(), record.value())).thenReturn(sampleEvent);
        when(materializationService.processLifecycleEvent(sampleEvent, record.value()))
                .thenThrow(new RuntimeException("Database connection timeout"));

        assertThatThrownBy(() -> consumer.onMessage(record, acknowledgment))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Database connection timeout");

        verify(acknowledgment, never()).acknowledge();
    }

    @Test
    @DisplayName("Null acknowledgment handle is handled safely without throwing NullPointerException")
    void testOnMessage_NullAcknowledgment_DoesNotThrow() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                "parcel.lifecycle.v1", 0, 100L, parcelId.toString(), "{\"eventId\":\"" + eventId + "\"}"
        );

        when(eventParser.parseAndValidate(record.key(), record.value())).thenReturn(sampleEvent);
        when(materializationService.processLifecycleEvent(sampleEvent, record.value()))
                .thenReturn(WebhookMaterializationResult.duplicate(eventId));

        consumer.onMessage(record, null);

        verify(eventParser).parseAndValidate(record.key(), record.value());
        verify(materializationService).processLifecycleEvent(sampleEvent, record.value());
    }

    @Test
    @DisplayName("Null constructor parameters are rejected")
    void testConstructorValidation() {
        assertThatThrownBy(() -> new WebhookLifecycleKafkaConsumer(null, materializationService))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new WebhookLifecycleKafkaConsumer(eventParser, null))
                .isInstanceOf(NullPointerException.class);
    }
}
