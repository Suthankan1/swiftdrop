package com.swiftdrop.deliveryservice.sse.consumer;

import com.swiftdrop.deliveryservice.sse.model.ParcelLifecycleSseEvent;
import com.swiftdrop.deliveryservice.sse.service.ParcelSseConnectionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ParcelLifecycleKafkaConsumerTest {

    @Mock
    private ParcelSseConnectionManager connectionManager;

    private ParcelLifecycleKafkaConsumer consumer;

    private static final UUID PARCEL_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID EVENT_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");

    @BeforeEach
    void setUp() {
        consumer = new ParcelLifecycleKafkaConsumer(connectionManager);
    }

    @Test
    @DisplayName("1. Valid Kafka lifecycle event: deserializes and broadcasts to connection manager")
    void testValidLifecycleEventBroadcast() {
        String json = """
                {
                  "eventId": "%s",
                  "eventType": "parcel.status.changed.v1",
                  "schemaVersion": "1.0.0",
                  "aggregateId": "%s",
                  "aggregateType": "PARCEL",
                  "aggregateVersion": 2,
                  "occurredAt": "2026-10-27T10:00:00Z",
                  "correlationId": "corr-12345",
                  "producer": "delivery-operations",
                  "payload": {
                    "parcelId": "%s",
                    "trackingNumber": "SWD-20261027-A1B2",
                    "oldStatus": "PICKED_UP",
                    "newStatus": "IN_TRANSIT",
                    "reason": null,
                    "merchantId": null,
                    "courierSub": "courier-1",
                    "cityOrZone": null
                  }
                }
                """.formatted(EVENT_ID, PARCEL_ID, PARCEL_ID);

        consumer.onLifecycleEvent(json, PARCEL_ID.toString());

        ArgumentCaptor<ParcelLifecycleSseEvent> captor = ArgumentCaptor.forClass(ParcelLifecycleSseEvent.class);
        verify(connectionManager).broadcastToParcel(org.mockito.ArgumentMatchers.eq(PARCEL_ID), captor.capture());

        ParcelLifecycleSseEvent event = captor.getValue();
        assertThat(event.eventId()).isEqualTo(EVENT_ID);
        assertThat(event.parcelId()).isEqualTo(PARCEL_ID);
        assertThat(event.trackingNumber()).isEqualTo("SWD-20261027-A1B2");
        assertThat(event.aggregateVersion()).isEqualTo(2L);
        assertThat(event.oldStatus()).isEqualTo("PICKED_UP");
        assertThat(event.newStatus()).isEqualTo("IN_TRANSIT");
        assertThat(event.reason()).isNull();
    }

    @Test
    @DisplayName("2. Malformed JSON message is dropped safely without crashing")
    void testMalformedJsonDroppedSafely() {
        consumer.onLifecycleEvent("{not valid json at all", PARCEL_ID.toString());
        verify(connectionManager, never()).broadcastToParcel(any(), any());
    }

    @Test
    @DisplayName("3. Unsupported event type is ignored")
    void testUnsupportedEventTypeIgnored() {
        String json = """
                {
                  "eventId": "%s",
                  "eventType": "parcel.created.v1",
                  "aggregateId": "%s",
                  "aggregateVersion": 1,
                  "occurredAt": "2026-10-27T10:00:00Z",
                  "payload": {
                    "parcelId": "%s"
                  }
                }
                """.formatted(EVENT_ID, PARCEL_ID, PARCEL_ID);

        consumer.onLifecycleEvent(json, PARCEL_ID.toString());
        verify(connectionManager, never()).broadcastToParcel(any(), any());
    }

    @Test
    @DisplayName("4. Partition key mismatch with aggregateId is dropped safely")
    void testPartitionKeyMismatchDropped() {
        String otherKey = UUID.randomUUID().toString();
        String json = """
                {
                  "eventId": "%s",
                  "eventType": "parcel.status.changed.v1",
                  "aggregateId": "%s",
                  "aggregateVersion": 2,
                  "occurredAt": "2026-10-27T10:00:00Z",
                  "payload": {
                    "parcelId": "%s",
                    "trackingNumber": "SWD-20261027-A1B2",
                    "oldStatus": "CREATED",
                    "newStatus": "PICKED_UP"
                  }
                }
                """.formatted(EVENT_ID, PARCEL_ID, PARCEL_ID);

        consumer.onLifecycleEvent(json, otherKey);
        verify(connectionManager, never()).broadcastToParcel(any(), any());
    }

    @Test
    @DisplayName("5. Missing required envelope or payload fields dropped safely")
    void testMissingRequiredFieldsDropped() {
        String json = """
                {
                  "eventId": "%s",
                  "eventType": "parcel.status.changed.v1"
                }
                """.formatted(EVENT_ID);

        consumer.onLifecycleEvent(json, PARCEL_ID.toString());
        verify(connectionManager, never()).broadcastToParcel(any(), any());
    }
}
