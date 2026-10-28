package com.swiftdrop.deliveryservice.sse;

import com.swiftdrop.deliveryservice.assignment.DeliveryAssignmentRepository;
import com.swiftdrop.deliveryservice.outbox.KafkaProducerConfiguration;
import com.swiftdrop.deliveryservice.sse.config.SseKafkaConsumerConfiguration;
import com.swiftdrop.deliveryservice.sse.config.SseProperties;
import com.swiftdrop.deliveryservice.sse.config.SseReplicaIdentityResolver;
import com.swiftdrop.deliveryservice.sse.consumer.ParcelLifecycleKafkaConsumer;
import com.swiftdrop.deliveryservice.sse.service.ParcelSseConnectionManager;
import com.swiftdrop.deliveryservice.sse.service.SseSubscriberSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = {
        KafkaProducerConfiguration.class,
        SseReplicaIdentityResolver.class,
        SseKafkaConsumerConfiguration.class,
        ParcelLifecycleKafkaConsumer.class,
        ParcelSseConnectionManager.class,
        SseProperties.class
})
@EmbeddedKafka(
        partitions = 1,
        topics = {"parcel.lifecycle.v1"},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers"
)
@TestPropertySource(properties = {
        "spring.kafka.consumer.auto-offset-reset=earliest",
        "spring.kafka.consumer.group-id=test-bridge-it-group"
})
class KafkaToSseBridgeIT {

    @MockitoBean
    private DeliveryAssignmentRepository assignmentRepository;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private ParcelSseConnectionManager connectionManager;

    @Autowired
    private KafkaListenerEndpointRegistry endpointRegistry;

    private static final UUID PARCEL_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final UUID EVENT_ID = UUID.fromString("66666666-6666-6666-6666-666666666666");

    @Test
    @DisplayName("Kafka-to-SSE Bridge Integration: message published to 'parcel.lifecycle.v1' is consumed and routed to parcel subscriber")
    void testKafkaToSseBridgeIntegration() throws Exception {
        // Wait for Kafka listener container to assign partitions
        for (MessageListenerContainer container : endpointRegistry.getListenerContainers()) {
            ContainerTestUtils.waitForAssignment(container, 1);
        }

        // 1. Register subscriber session for PARCEL_ID
        SseEmitter emitter = connectionManager.registerSession(
                PARCEL_ID,
                "user-subscriber-1",
                false,
                Instant.now().plus(1, ChronoUnit.HOURS)
        );
        assertThat(emitter).isNotNull();

        List<SseSubscriberSession> subscribers = connectionManager.getSubscribersForParcel(PARCEL_ID);
        assertThat(subscribers).hasSize(1);
        SseSubscriberSession session = subscribers.get(0);
        assertThat(session.getLastDeliveredVersion()).isEqualTo(0L);

        // 2. Publish domain event to Kafka 'parcel.lifecycle.v1' with partition key = PARCEL_ID
        String jsonPayload = """
                {
                  "eventId": "%s",
                  "eventType": "parcel.status.changed.v1",
                  "schemaVersion": "1.0.0",
                  "aggregateId": "%s",
                  "aggregateType": "PARCEL",
                  "aggregateVersion": 1,
                  "occurredAt": "2026-10-27T10:30:00Z",
                  "correlationId": "corr-bridge-it",
                  "producer": "delivery-operations",
                  "payload": {
                    "parcelId": "%s",
                    "trackingNumber": "SWD-20261027-BRIDGE",
                    "oldStatus": "CREATED",
                    "newStatus": "PICKED_UP",
                    "reason": null,
                    "merchantId": null,
                    "courierSub": "courier-1",
                    "cityOrZone": "Colombo-03"
                  }
                }
                """.formatted(EVENT_ID, PARCEL_ID, PARCEL_ID);

        kafkaTemplate.send("parcel.lifecycle.v1", PARCEL_ID.toString(), jsonPayload).get(5, TimeUnit.SECONDS);

        // 3. Await event delivery to session
        long deadline = System.currentTimeMillis() + 10000;
        while (System.currentTimeMillis() < deadline && session.getLastDeliveredVersion() < 1L) {
            Thread.sleep(100);
        }

        // 4. Assert that the SSE session received the version 1 lifecycle transition
        assertThat(session.getLastDeliveredVersion()).isEqualTo(1L);
    }
}
