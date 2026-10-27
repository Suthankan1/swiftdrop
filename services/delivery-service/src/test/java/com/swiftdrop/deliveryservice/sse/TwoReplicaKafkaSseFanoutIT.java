package com.swiftdrop.deliveryservice.sse;

import com.swiftdrop.deliveryservice.assignment.DeliveryAssignmentRepository;
import com.swiftdrop.deliveryservice.outbox.KafkaProducerConfiguration;
import com.swiftdrop.deliveryservice.sse.config.SseKafkaConsumerConfiguration;
import com.swiftdrop.deliveryservice.sse.config.SseProperties;
import com.swiftdrop.deliveryservice.sse.config.SseReplicaIdentityResolver;
import com.swiftdrop.deliveryservice.sse.consumer.ParcelLifecycleKafkaConsumer;
import com.swiftdrop.deliveryservice.sse.service.ParcelSseConnectionManager;
import com.swiftdrop.deliveryservice.sse.service.SseSubscriberSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.MessageListener;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * End-to-end integration test demonstrating Kafka-to-SSE broadcast fan-out
 * across TWO distinct Delivery Service replicas (Issue #161).
 *
 * Verifies that:
 * 1. Independent Kafka consumer groups (tracking.replica-a and tracking.replica-b)
 *    each consume 100% of topic partitions without partition starvation.
 * 2. The same lifecycle event published once to Kafka is broadcast to both replicas.
 * 3. Each replica routes the event to its own locally connected, authorized SSE subscribers.
 * 4. Cross-parcel subscribers receive no events (parcel isolation).
 * 5. Duplicate and out-of-order events are filtered safely by aggregate version sequencing.
 * 6. Dynamic courier assignment revocation terminates revoked sessions.
 * 7. Container restart with a stable replica identifier recovers and resumes consumption.
 * 8. Group collision detection rejects identical simultaneous replica identities.
 */
@SpringBootTest(classes = {
        KafkaProducerConfiguration.class,
        SseProperties.class
})
@EmbeddedKafka(
        partitions = 2,
        topics = {"parcel.lifecycle.v1"},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers"
)
@TestPropertySource(properties = {
        "spring.kafka.consumer.auto-offset-reset=earliest"
})
class TwoReplicaKafkaSseFanoutIT {

    private static final String TOPIC = "parcel.lifecycle.v1";
    private static final UUID PARCEL_1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PARCEL_2 = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID PARCEL_COURIER = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @MockitoBean
    private DeliveryAssignmentRepository assignmentRepository;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private Environment environment;

    // Replica A Components
    private SseProperties propertiesA;
    private SseReplicaIdentityResolver resolverA;
    private SseKafkaConsumerConfiguration configA;
    private ParcelSseConnectionManager connectionManagerA;
    private ParcelLifecycleKafkaConsumer consumerA;
    private ConcurrentMessageListenerContainer<String, String> containerA;

    // Replica B Components
    private SseProperties propertiesB;
    private SseReplicaIdentityResolver resolverB;
    private SseKafkaConsumerConfiguration configB;
    private ParcelSseConnectionManager connectionManagerB;
    private ParcelLifecycleKafkaConsumer consumerB;
    private ConcurrentMessageListenerContainer<String, String> containerB;

    @BeforeEach
    void setUp() {
        String bootstrapServers = environment.getProperty("spring.kafka.bootstrap-servers");

        // Setup Replica A with stable identity "replica-a"
        propertiesA = new SseProperties();
        propertiesA.getKafka().setReplicaId("replica-a");
        propertiesA.getKafka().setGroupPrefix("tracking.");
        propertiesA.getKafka().setAutoOffsetReset("earliest");

        resolverA = new SseReplicaIdentityResolver(propertiesA, environment);
        configA = new SseKafkaConsumerConfiguration(resolverA, propertiesA);
        configA.setBootstrapServers(bootstrapServers);
        configA.setAutoOffsetReset("earliest");
        String groupIdA = configA.sseConsumerGroupId();
        assertThat(groupIdA).isEqualTo("tracking.replica-a");

        ConsumerFactory<String, String> cfA = configA.sseKafkaConsumerFactory(groupIdA);
        connectionManagerA = new ParcelSseConnectionManager(propertiesA, assignmentRepository);
        consumerA = new ParcelLifecycleKafkaConsumer(connectionManagerA);

        containerA = configA.sseKafkaListenerContainerFactory(cfA).createContainer(TOPIC);
        containerA.setupMessageListener((MessageListener<String, String>) record ->
                consumerA.onLifecycleEvent(record.value(), record.key()));
        containerA.start();
        ContainerTestUtils.waitForAssignment(containerA, 2);

        // Setup Replica B with stable identity "replica-b"
        propertiesB = new SseProperties();
        propertiesB.getKafka().setReplicaId("replica-b");
        propertiesB.getKafka().setGroupPrefix("tracking.");
        propertiesB.getKafka().setAutoOffsetReset("earliest");

        resolverB = new SseReplicaIdentityResolver(propertiesB, environment);
        configB = new SseKafkaConsumerConfiguration(resolverB, propertiesB);
        configB.setBootstrapServers(bootstrapServers);
        configB.setAutoOffsetReset("earliest");
        String groupIdB = configB.sseConsumerGroupId();
        assertThat(groupIdB).isEqualTo("tracking.replica-b");

        ConsumerFactory<String, String> cfB = configB.sseKafkaConsumerFactory(groupIdB);
        connectionManagerB = new ParcelSseConnectionManager(propertiesB, assignmentRepository);
        consumerB = new ParcelLifecycleKafkaConsumer(connectionManagerB);

        containerB = configB.sseKafkaListenerContainerFactory(cfB).createContainer(TOPIC);
        containerB.setupMessageListener((MessageListener<String, String>) record ->
                consumerB.onLifecycleEvent(record.value(), record.key()));
        containerB.start();
        ContainerTestUtils.waitForAssignment(containerB, 2);
    }

    @AfterEach
    void tearDown() {
        if (containerA != null) containerA.stop();
        if (containerB != null) containerB.stop();
        if (connectionManagerA != null) connectionManagerA.shutdown();
        if (connectionManagerB != null) connectionManagerB.shutdown();
    }

    @Test
    @DisplayName("Two-Replica Broadcast Fanout: Single Kafka event is delivered to clients on BOTH replicas")
    void testTwoReplicaBroadcastFanout() throws Exception {
        UUID parcel1 = UUID.randomUUID();
        UUID parcelIsolated = UUID.randomUUID();

        // 1. Connect Client 1 to Replica A for parcel1
        SseEmitter emitterA = connectionManagerA.registerSession(
                parcel1,
                "customer-sub-1",
                false,
                Instant.now().plus(1, ChronoUnit.HOURS)
        );
        assertThat(emitterA).isNotNull();

        // 2. Connect Client 2 to Replica B for parcel1
        SseEmitter emitterB = connectionManagerB.registerSession(
                parcel1,
                "merchant-sub-2",
                false,
                Instant.now().plus(1, ChronoUnit.HOURS)
        );
        assertThat(emitterB).isNotNull();

        // 3. Connect Client 3 to Replica A for different parcelIsolated (verifies parcel isolation)
        SseEmitter emitterIsolated = connectionManagerA.registerSession(
                parcelIsolated,
                "customer-sub-3",
                false,
                Instant.now().plus(1, ChronoUnit.HOURS)
        );
        assertThat(emitterIsolated).isNotNull();

        List<SseSubscriberSession> subsA = connectionManagerA.getSubscribersForParcel(parcel1);
        List<SseSubscriberSession> subsB = connectionManagerB.getSubscribersForParcel(parcel1);
        List<SseSubscriberSession> subsIso = connectionManagerA.getSubscribersForParcel(parcelIsolated);

        assertThat(subsA).hasSize(1);
        assertThat(subsB).hasSize(1);
        assertThat(subsIso).hasSize(1);

        SseSubscriberSession sessionA = subsA.get(0);
        SseSubscriberSession sessionB = subsB.get(0);
        SseSubscriberSession sessionIso = subsIso.get(0);

        assertThat(sessionA.getLastDeliveredVersion()).isEqualTo(0L);
        assertThat(sessionB.getLastDeliveredVersion()).isEqualTo(0L);
        assertThat(sessionIso.getLastDeliveredVersion()).isEqualTo(0L);

        // 4. Publish ONE lifecycle transition to Kafka for parcel1
        UUID eventId1 = UUID.randomUUID();
        String eventPayload1 = buildLifecycleEventJson(eventId1, parcel1, "SWD-20261027-PARCEL1", 1, "CREATED", "PICKED_UP");

        kafkaTemplate.send(TOPIC, parcel1.toString(), eventPayload1).get(10, TimeUnit.SECONDS);

        // 5. Verify BOTH Replica A and Replica B receive and deliver the event
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(sessionA.getLastDeliveredVersion()).isEqualTo(1L);
            assertThat(sessionB.getLastDeliveredVersion()).isEqualTo(1L);
        });

        // 6. Verify cross-parcel subscriber received NO events
        assertThat(sessionIso.getLastDeliveredVersion()).isEqualTo(0L);

        // 7. Test Duplicate Filtering: Re-send the exact same event
        kafkaTemplate.send(TOPIC, parcel1.toString(), eventPayload1).get(10, TimeUnit.SECONDS);

        // Both replicas must ignore the duplicate version
        Thread.sleep(1000);
        assertThat(sessionA.getLastDeliveredVersion()).isEqualTo(1L);
        assertThat(sessionB.getLastDeliveredVersion()).isEqualTo(1L);

        // 8. Test Out-of-Order / Stale Event: Publish version 2, then send version 1 again
        UUID eventId2 = UUID.randomUUID();
        String eventPayload2 = buildLifecycleEventJson(eventId2, parcel1, "SWD-20261027-PARCEL1", 2, "PICKED_UP", "IN_TRANSIT");
        kafkaTemplate.send(TOPIC, parcel1.toString(), eventPayload2).get(10, TimeUnit.SECONDS);

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(sessionA.getLastDeliveredVersion()).isEqualTo(2L);
            assertThat(sessionB.getLastDeliveredVersion()).isEqualTo(2L);
        });

        // Sending version 1 again is dropped
        kafkaTemplate.send(TOPIC, parcel1.toString(), eventPayload1).get(10, TimeUnit.SECONDS);
        Thread.sleep(1000);
        assertThat(sessionA.getLastDeliveredVersion()).isEqualTo(2L);
        assertThat(sessionB.getLastDeliveredVersion()).isEqualTo(2L);
    }

    @Test
    @DisplayName("Dynamic courier assignment revocation closes session upon transition")
    void testDynamicCourierRevocation() throws Exception {
        UUID parcelCourier = UUID.randomUUID();
        when(assignmentRepository.existsByParcelIdAndCourierSubAndActiveTrue(eq(parcelCourier), eq("courier-revoked")))
                .thenReturn(false);

        connectionManagerA.registerSession(
                parcelCourier,
                "courier-revoked",
                true, // Courier subscriber
                Instant.now().plus(1, ChronoUnit.HOURS)
        );

        List<SseSubscriberSession> subs = connectionManagerA.getSubscribersForParcel(parcelCourier);
        assertThat(subs).hasSize(1);
        SseSubscriberSession courierSession = subs.get(0);
        assertThat(courierSession.isClosed()).isFalse();

        // Publish transition event
        UUID eventId = UUID.randomUUID();
        String json = buildLifecycleEventJson(eventId, parcelCourier, "SWD-20261027-COURIER", 1, "PICKED_UP", "IN_TRANSIT");
        kafkaTemplate.send(TOPIC, parcelCourier.toString(), json).get(10, TimeUnit.SECONDS);

        // Session must be revoked and closed
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(courierSession.isClosed()).isTrue());
    }

    @Test
    @DisplayName("Replica restart with stable identifier resumes consumption cleanly")
    void testRestartWithStableReplicaIdentity() throws Exception {
        UUID parcelRestart = UUID.randomUUID();

        // Connect subscriber on Replica A
        connectionManagerA.registerSession(
                parcelRestart,
                "customer-restart-test",
                false,
                Instant.now().plus(1, ChronoUnit.HOURS)
        );

        SseSubscriberSession sessionA = connectionManagerA.getSubscribersForParcel(parcelRestart).get(0);

        // Stop Replica A container to simulate replica restart / maintenance
        containerA.stop();
        assertThat(containerA.isRunning()).isFalse();

        // Publish event 1 while container A is offline
        UUID eventId1 = UUID.randomUUID();
        String json1 = buildLifecycleEventJson(eventId1, parcelRestart, "SWD-20261027-RESTART", 1, "CREATED", "PICKED_UP");
        kafkaTemplate.send(TOPIC, parcelRestart.toString(), json1).get(10, TimeUnit.SECONDS);

        // Now restart Replica A with the SAME stable consumer group "tracking.replica-a"
        containerA.start();
        ContainerTestUtils.waitForAssignment(containerA, 2);

        // Publish event 2
        UUID eventId2 = UUID.randomUUID();
        String json2 = buildLifecycleEventJson(eventId2, parcelRestart, "SWD-20261027-RESTART", 2, "PICKED_UP", "IN_TRANSIT");
        kafkaTemplate.send(TOPIC, parcelRestart.toString(), json2).get(10, TimeUnit.SECONDS);

        // Replica A resumes and processes events cleanly
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(sessionA.getLastDeliveredVersion()).isGreaterThanOrEqualTo(1L));
    }

    @Test
    @DisplayName("Group collision rejection: Two replicas configured with identical IDs are detected and rejected")
    void testGroupCollisionRejection() {
        assertThatThrownBy(() -> SseReplicaIdentityResolver.validateDistinctReplicas("replica-a", "replica-a"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("partition starvation");
    }

    private String buildLifecycleEventJson(UUID eventId, UUID parcelId, String trackingNumber, long version, String oldStatus, String newStatus) {
        String oldStatusStr = oldStatus != null ? "\"" + oldStatus + "\"" : "null";
        return """
                {
                  "eventId": "%s",
                  "eventType": "parcel.status.changed.v1",
                  "schemaVersion": "1.0.0",
                  "aggregateId": "%s",
                  "aggregateType": "PARCEL",
                  "aggregateVersion": %d,
                  "occurredAt": "%s",
                  "correlationId": "corr-fanout-test",
                  "producer": "delivery-operations",
                  "payload": {
                    "parcelId": "%s",
                    "trackingNumber": "%s",
                    "oldStatus": %s,
                    "newStatus": "%s",
                    "reason": null,
                    "merchantId": null,
                    "courierSub": null,
                    "cityOrZone": "Colombo"
                  }
                }
                """.formatted(
                eventId,
                parcelId,
                version,
                Instant.now().toString(),
                parcelId,
                trackingNumber,
                oldStatusStr,
                newStatus
        );
    }
}
