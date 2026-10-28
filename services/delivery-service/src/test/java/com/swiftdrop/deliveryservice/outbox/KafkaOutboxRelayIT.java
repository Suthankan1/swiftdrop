package com.swiftdrop.deliveryservice.outbox;

import com.swiftdrop.deliveryservice.core.Parcel;
import com.swiftdrop.deliveryservice.core.ParcelRepository;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EmbeddedKafka(
        partitions = 3,
        topics = {"parcel.lifecycle.v1"},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers"
)
class KafkaOutboxRelayIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("delivery_db")
            .withUsername("delivery_app")
            .withPassword("delivery_secret");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("swiftdrop.outbox.relay.enabled", () -> "false"); // Manual invocation in test
    }

    @Autowired
    private ParcelRepository parcelRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private KafkaOutboxRelay outboxRelay;

    @Autowired
    private EmbeddedKafkaBroker embeddedKafkaBroker;

    @Autowired
    private OutboxRelayProperties relayProperties;

    @Autowired
    private io.micrometer.core.instrument.MeterRegistry meterRegistry;

    @Autowired
    private OutboxMetrics outboxMetrics;

    private Consumer<String, String> consumer;
    private static final String TOPIC = "parcel.lifecycle.v1";
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();

    @BeforeEach
    void setUpConsumer() {
        Map<String, Object> consumerProps = KafkaTestUtils.consumerProps("test-relay-group-" + UUID.randomUUID(), "true", embeddedKafkaBroker);
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        DefaultKafkaConsumerFactory<String, String> cf = new DefaultKafkaConsumerFactory<>(consumerProps);
        consumer = cf.createConsumer();
        embeddedKafkaBroker.consumeFromAnEmbeddedTopic(consumer, TOPIC);
    }

    @AfterEach
    void tearDown() {
        if (consumer != null) {
            consumer.close();
        }
    }

    private Parcel createParcel(UUID parcelId) {
        Parcel parcel = new Parcel(
                parcelId,
                "SWD-KAFKA-" + UUID.randomUUID().toString().substring(0, 8),
                "sender-customer-kafka",
                UUID.randomUUID(),
                UUID.randomUUID(),
                "2026.1",
                200000L,
                "LKR",
                new BigDecimal("1.500")
        );
        return parcelRepository.saveAndFlush(parcel);
    }

    @Test
    @DisplayName("1. Real Kafka Relay: dispatches event to broker, verifies broker ACK and status=PUBLISHED")
    void testRealKafkaPublishAndAck() throws Exception {
        UUID parcelId = UUID.randomUUID();
        createParcel(parcelId);

        UUID eventId = UUID.randomUUID();
        String payload = "{\"eventId\":\"" + eventId + "\",\"aggregateId\":\"" + parcelId + "\",\"status\":\"PICKED_UP\"}";

        OutboxEvent event = new OutboxEvent(
                eventId,
                "PARCEL",
                parcelId,
                2L,
                "parcel.status.changed.v1",
                "1.0.0",
                payload,
                TOPIC,
                parcelId.toString()
        );
        event.setNextAttemptAt(Instant.now().minusSeconds(2));
        outboxEventRepository.saveAndFlush(event);

        // Execute relay processing
        int processed = outboxRelay.processBatch();
        assertThat(processed).isEqualTo(1);

        // Verify message received on broker
        ConsumerRecords<String, String> records = KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(10));
        assertThat(records.count()).isGreaterThanOrEqualTo(1);

        ConsumerRecord<String, String> matchingRecord = null;
        for (ConsumerRecord<String, String> record : records) {
            if (parcelId.toString().equals(record.key())) {
                matchingRecord = record;
                break;
            }
        }

        assertThat(matchingRecord).isNotNull();
        assertThat(matchingRecord.key()).isEqualTo(parcelId.toString());
        com.fasterxml.jackson.databind.JsonNode expectedJson = objectMapper.readTree(payload);
        com.fasterxml.jackson.databind.JsonNode actualJson = objectMapper.readTree(matchingRecord.value());
        assertThat(actualJson).isEqualTo(expectedJson);

        // Verify outbox row status in database
        OutboxEvent updated = outboxEventRepository.findById(eventId).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(updated.getAcknowledgedAt()).isNotNull();
        assertThat(updated.getLastError()).isNull();
    }

    @Test
    @DisplayName("2. Partition Key and Aggregate Version Ordering: sequential events on same partition preserve order")
    void testSequentialOrderingPreserved() throws Exception {
        UUID parcelId = UUID.randomUUID();
        createParcel(parcelId);

        OutboxEvent event1 = new OutboxEvent(
                UUID.randomUUID(),
                "PARCEL",
                parcelId,
                2L,
                "parcel.status.changed.v1",
                "1.0.0",
                "{\"version\":2}",
                TOPIC,
                parcelId.toString()
        );

        OutboxEvent event2 = new OutboxEvent(
                UUID.randomUUID(),
                "PARCEL",
                parcelId,
                3L,
                "parcel.status.changed.v1",
                "1.0.0",
                "{\"version\":3}",
                TOPIC,
                parcelId.toString()
        );

        event1.setNextAttemptAt(Instant.now().minusSeconds(2));
        event2.setNextAttemptAt(Instant.now().minusSeconds(2));
        outboxEventRepository.saveAllAndFlush(List.of(event1, event2));

        // Process relay
        int processed = outboxRelay.processBatch();
        assertThat(processed).isEqualTo(2);

        // Read records
        ConsumerRecords<String, String> records = KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(10));
        List<ConsumerRecord<String, String>> parcelRecords = new ArrayList<>();
        for (ConsumerRecord<String, String> record : records) {
            if (parcelId.toString().equals(record.key())) {
                parcelRecords.add(record);
            }
        }

        assertThat(parcelRecords).hasSize(2);
        // Both records for same parcel must route to the SAME partition
        assertThat(parcelRecords.get(0).partition()).isEqualTo(parcelRecords.get(1).partition());
        // Versions arrive in ascending order
        com.fasterxml.jackson.databind.JsonNode v1Node = objectMapper.readTree(parcelRecords.get(0).value());
        com.fasterxml.jackson.databind.JsonNode v2Node = objectMapper.readTree(parcelRecords.get(1).value());
        assertThat(v1Node.get("version").asInt()).isEqualTo(2);
        assertThat(v2Node.get("version").asInt()).isEqualTo(3);
    }

    @Test
    @DisplayName("3. Crash / Restart Recovery: worker crash during IN_FLIGHT is reclaimed after lease expiry")
    void testCrashAndRestartRecovery() {
        UUID parcelId = UUID.randomUUID();
        createParcel(parcelId);

        UUID crashedEventId = UUID.randomUUID();
        String payload = "{\"recovered\":true}";

        OutboxEvent crashedEvent = new OutboxEvent(
                crashedEventId,
                "PARCEL",
                parcelId,
                5L,
                "parcel.status.changed.v1",
                "1.0.0",
                payload,
                TOPIC,
                parcelId.toString()
        );
        crashedEvent.setStatus(OutboxStatus.IN_FLIGHT);
        crashedEvent.setClaimedBy("crashed-pod-999");
        crashedEvent.setClaimExpiresAt(Instant.now().minusSeconds(5)); // Lease expired
        outboxEventRepository.saveAndFlush(crashedEvent);

        // Process relay (new worker picking up work)
        int processed = outboxRelay.processBatch();
        assertThat(processed).isEqualTo(1);

        // Verify broker received the event
        ConsumerRecords<String, String> records = KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(10));
        boolean found = false;
        for (ConsumerRecord<String, String> record : records) {
            if (parcelId.toString().equals(record.key())) {
                found = true;
                break;
            }
        }
        assertThat(found).isTrue();

        // Verify status in DB is now PUBLISHED
        OutboxEvent recovered = outboxEventRepository.findById(crashedEventId).orElseThrow();
        assertThat(recovered.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(recovered.getAcknowledgedAt()).isNotNull();
    }

    @Test
    @DisplayName("4. Broker outage recovery: committed parcel/outbox survives failure and publishes unchanged after bounded backoff")
    void testBrokerOutageRecoveryPreservesCanonicalTransaction() throws Exception {
        long parcelsBefore = parcelRepository.count();
        UUID parcelId = UUID.randomUUID();
        createParcel(parcelId);
        UUID eventId = UUID.randomUUID();
        String payload = "{\"eventId\":\"" + eventId + "\",\"aggregateId\":\"" + parcelId
                + "\",\"aggregateVersion\":1,\"correlationId\":\"issue-164-redacted\"}";
        OutboxEvent event = new OutboxEvent(eventId, "PARCEL", parcelId, 1L,
                "parcel.status.changed.v1", "1.0.0", payload, TOPIC, parcelId.toString());
        event.setNextAttemptAt(Instant.now().minusSeconds(1));
        outboxEventRepository.saveAndFlush(event);

        UUID unrelatedParcelId = UUID.randomUUID();
        createParcel(unrelatedParcelId);
        UUID unrelatedEventId = UUID.randomUUID();
        OutboxEvent unrelated = new OutboxEvent(unrelatedEventId, "PARCEL", unrelatedParcelId, 1L,
                "parcel.status.changed.v1", "1.0.0", "{\"unrelated\":true}", TOPIC, unrelatedParcelId.toString());
        unrelated.setNextAttemptAt(Instant.now().plusSeconds(300));
        outboxEventRepository.saveAndFlush(unrelated);

        double failuresBefore = meterRegistry.find("swiftdrop.outbox.publish.total")
                .tag("outcome", "failure").counter().count();
        long started = System.nanoTime();
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> unavailableKafka = mock(KafkaTemplate.class);
        when(unavailableKafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("injected isolated broker outage")));
        KafkaOutboxRelay failingRelay = new KafkaOutboxRelay(
                outboxEventRepository, relayProperties, unavailableKafka, outboxMetrics);

        assertThat(failingRelay.processBatch()).isZero();
        OutboxEvent failed = outboxEventRepository.findById(eventId).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(failed.getAttemptCount()).isEqualTo(1);
        assertThat(failed.getNextAttemptAt()).isAfter(failed.getLastAttemptAt());
        assertThat(objectMapper.readTree(failed.getPayload())).isEqualTo(objectMapper.readTree(payload));
        assertThat(parcelRepository.existsById(parcelId)).isTrue();
        assertThat(parcelRepository.count()).isEqualTo(parcelsBefore + 2);
        assertThat(outboxEventRepository.findById(unrelatedEventId).orElseThrow().getAttemptCount()).isZero();
        assertThat(meterRegistry.find("swiftdrop.outbox.publish.total")
                .tag("outcome", "failure").counter().count()).isEqualTo(failuresBefore + 1.0d);

        awaitCondition(() -> {
            outboxRelay.processBatch();
            return outboxEventRepository.findById(eventId)
                    .map(candidate -> candidate.getStatus() == OutboxStatus.PUBLISHED)
                    .orElse(false);
        }, Duration.ofSeconds(15));

        OutboxEvent recovered = outboxEventRepository.findById(eventId).orElseThrow();
        assertThat(recovered.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(recovered.getAttemptCount()).isEqualTo(2);
        assertThat(objectMapper.readTree(recovered.getPayload())).isEqualTo(objectMapper.readTree(payload));
        assertThat(parcelRepository.count()).isEqualTo(parcelsBefore + 2);
        assertThat(outboxEventRepository.findById(unrelatedEventId).orElseThrow().getAttemptCount()).isZero();

        ConsumerRecords<String, String> records = KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(10));
        List<String> recoveredPayloads = new ArrayList<>();
        for (ConsumerRecord<String, String> record : records.records(TOPIC)) {
            if (parcelId.toString().equals(record.key())) {
                recoveredPayloads.add(record.value());
            }
        }
        assertThat(recoveredPayloads).hasSize(1);
        assertThat(objectMapper.readTree(recoveredPayloads.getFirst()))
                .isEqualTo(objectMapper.readTree(payload));

        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertThat(elapsedMillis).isLessThan(20_000L);
        System.out.printf("ISSUE164_OUTBOX_RECOVERY elapsedMs=%d proposedTestBudgetMs=20000%n", elapsedMillis);
    }

    private static void awaitCondition(Callable<Boolean> condition, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        Throwable lastFailure = null;
        while (System.nanoTime() < deadline) {
            try {
                if (condition.call()) {
                    return;
                }
            } catch (Throwable failure) {
                lastFailure = failure;
            }
            Thread.sleep(100);
        }
        if (lastFailure != null) {
            throw new AssertionError("Condition did not complete within " + timeout, lastFailure);
        }
        throw new AssertionError("Condition did not complete within " + timeout);
    }
}
