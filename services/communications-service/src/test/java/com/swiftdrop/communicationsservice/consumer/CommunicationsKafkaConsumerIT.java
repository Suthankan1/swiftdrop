package com.swiftdrop.communicationsservice.consumer;

import com.swiftdrop.communicationsservice.client.AccountsRecipientContactClient;
import com.swiftdrop.communicationsservice.client.DeliveryEventAuthorization;
import com.swiftdrop.communicationsservice.client.DeliveryRecipientAuthorizationClient;
import com.swiftdrop.communicationsservice.client.PermittedRecipientContact;
import com.swiftdrop.communicationsservice.inbox.ConsumedEvent;
import com.swiftdrop.communicationsservice.inbox.ConsumedEventRepository;
import com.swiftdrop.communicationsservice.notification.NotificationChannel;
import com.swiftdrop.communicationsservice.notification.NotificationJob;
import com.swiftdrop.communicationsservice.notification.NotificationJobRepository;
import com.swiftdrop.communicationsservice.notification.NotificationStatus;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EmbeddedKafka(
        partitions = 3,
        topics = {"parcel.lifecycle.v1"},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers"
)
class CommunicationsKafkaConsumerIT {

    private static final String TOPIC = "parcel.lifecycle.v1";
    private static final String CONSUMER_GROUP = "communications.notifications.v1";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("communications_db")
            .withUsername("communications_app")
            .withPassword("communications_secret");

    @DynamicPropertySource
    static void configureDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("swiftdrop.kafka.consumer.enabled", () -> "true");
        registry.add("swiftdrop.kafka.topics.parcel-lifecycle", () -> TOPIC);
        registry.add("spring.kafka.consumer.group-id", () -> CONSUMER_GROUP);
    }

    @Autowired
    private EmbeddedKafkaBroker embeddedKafka;

    @Autowired
    private ConsumedEventRepository consumedEventRepository;

    @Autowired
    private NotificationJobRepository notificationJobRepository;

    @Autowired
    private ParcelLifecycleKafkaConsumer lifecycleConsumer;

    @MockitoBean
    private DeliveryRecipientAuthorizationClient deliveryAuthorizationClient;

    @MockitoBean
    private AccountsRecipientContactClient accountsContactClient;

    private KafkaTemplate<String, String> producerTemplate;

    @BeforeEach
    void setUp() {
        Map<String, Object> producerProps = KafkaTestUtils.producerProps(embeddedKafka);
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producerTemplate = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(producerProps));
    }

    private String buildEventJson(UUID eventId, UUID parcelId, String newStatus, String oldStatus, String reason) {
        String reasonSnippet = reason != null ? "\"reason\": \"" + reason + "\"," : "";
        String oldStatusSnippet = oldStatus != null ? "\"oldStatus\": \"" + oldStatus + "\"," : "\"oldStatus\": null,";
        return """
                {
                  "eventId": "%s",
                  "eventType": "parcel.status.changed.v1",
                  "schemaVersion": "1.0.0",
                  "aggregateId": "%s",
                  "aggregateType": "PARCEL",
                  "aggregateVersion": 1,
                  "occurredAt": "%s",
                  "correlationId": "corr-%s",
                  "producer": "delivery-operations",
                  "payload": {
                    "parcelId": "%s",
                    "trackingNumber": "SWD-20261026-A1B2",
                    %s
                    "newStatus": "%s",
                    %s
                    "merchantId": null,
                    "courierSub": "courier-42",
                    "cityOrZone": "Colombo-03"
                  }
                }
                """.formatted(eventId, parcelId, Instant.now().toString(), eventId, parcelId, oldStatusSnippet, newStatus, reasonSnippet);
    }

    private void awaitCondition(java.util.function.BooleanSupplier condition, Duration timeout) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(100);
        }
    }

    @Test
    @DisplayName("Integration A: Real publish and consume of parcel.status.changed.v1 event commits inbox and notification job")
    void testRealPublishAndConsumeLifecycleEvent() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID parcelId = UUID.randomUUID();
        String recipientSub = "sub-buyer-" + UUID.randomUUID();

        DeliveryEventAuthorization auth = new DeliveryEventAuthorization(
                true,
                parcelId,
                eventId,
                recipientSub,
                null,
                "SWD-20261026-A1B2",
                "DELIVERED",
                "parcel.status.changed.v1",
                1L,
                1L,
                true,
                false,
                "AUTHORIZED"
        );

        PermittedRecipientContact contact = new PermittedRecipientContact(
                recipientSub,
                "buyer@test.test",
                null,
                "Authorized Buyer",
                true,
                true,
                false,
                "ACTIVE",
                "ELIGIBLE"
        );

        when(deliveryAuthorizationClient.authorizeEventRecipient(eq(eventId), eq(parcelId), any(), any(), anyBoolean()))
                .thenReturn(auth);
        when(accountsContactClient.resolveContact(eq(recipientSub), eq(NotificationSchedulingService.NOTIFICATION_PURPOSE), eq(NotificationChannel.EMAIL)))
                .thenReturn(Optional.of(contact));

        String json = buildEventJson(eventId, parcelId, "DELIVERED", "OUT_FOR_DELIVERY", null);

        // Publish to Kafka topic
        producerTemplate.send(TOPIC, parcelId.toString(), json).get();

        // Wait for asynchronous consumer processing and PostgreSQL commit
        awaitCondition(() -> consumedEventRepository.existsByIdConsumerNameAndIdEventId(CONSUMER_GROUP, eventId),
                Duration.ofSeconds(10));

        assertThat(consumedEventRepository.existsByIdConsumerNameAndIdEventId(CONSUMER_GROUP, eventId)).isTrue();

        ConsumedEvent consumedEvent = consumedEventRepository.findByIdConsumerNameAndIdEventId(CONSUMER_GROUP, eventId).orElseThrow();
        assertThat(consumedEvent.getParcelId()).isEqualTo(parcelId);
        assertThat(consumedEvent.getEventType()).isEqualTo("parcel.status.changed.v1");
        assertThat(consumedEvent.getVersion()).isEqualTo(1);

        Optional<NotificationJob> jobOpt = notificationJobRepository.findByEventIdAndRecipientSubAndChannel(
                eventId, recipientSub, NotificationChannel.EMAIL);
        assertThat(jobOpt).isPresent();
        NotificationJob job = jobOpt.get();
        assertThat(job.getStatus()).isEqualTo(NotificationStatus.PENDING);
        assertThat(job.getAttempts()).isZero();
        assertThat(job.getChannel()).isEqualTo(NotificationChannel.EMAIL);
        assertThat(job.getTemplate()).isEqualTo("PARCEL_DELIVERED");
        assertThat(job.getDestinationRef()).isEqualTo("sub:" + recipientSub);
        // Verify ZERO email PII stored
        assertThat(job.getDestinationRef()).doesNotContain("@");
    }

    @Test
    @DisplayName("Integration B: Duplicate Kafka delivery of same event ID produces exactly one inbox receipt and one notification job")
    void testDuplicateDeliveryHandledSafely() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID parcelId = UUID.randomUUID();
        String recipientSub = "sub-buyer-" + UUID.randomUUID();

        DeliveryEventAuthorization auth = new DeliveryEventAuthorization(
                true,
                parcelId,
                eventId,
                recipientSub,
                null,
                "SWD-20261026-A1B2",
                "PICKED_UP",
                "parcel.status.changed.v1",
                1L,
                1L,
                false,
                false,
                "AUTHORIZED"
        );

        PermittedRecipientContact contact = new PermittedRecipientContact(
                recipientSub,
                "buyer@test.test",
                null,
                "Authorized Buyer",
                true,
                true,
                false,
                "ACTIVE",
                "ELIGIBLE"
        );

        when(deliveryAuthorizationClient.authorizeEventRecipient(eq(eventId), eq(parcelId), any(), any(), anyBoolean()))
                .thenReturn(auth);
        when(accountsContactClient.resolveContact(eq(recipientSub), eq(NotificationSchedulingService.NOTIFICATION_PURPOSE), eq(NotificationChannel.EMAIL)))
                .thenReturn(Optional.of(contact));

        String json = buildEventJson(eventId, parcelId, "PICKED_UP", "CREATED", null);

        // Publish first time
        producerTemplate.send(TOPIC, parcelId.toString(), json).get();

        // Wait for first delivery to be committed
        awaitCondition(() -> consumedEventRepository.existsByIdConsumerNameAndIdEventId(CONSUMER_GROUP, eventId),
                Duration.ofSeconds(10));
        assertThat(consumedEventRepository.existsByIdConsumerNameAndIdEventId(CONSUMER_GROUP, eventId)).isTrue();

        // Publish duplicate event with identical eventId and partition key
        producerTemplate.send(TOPIC, parcelId.toString(), json).get();

        // Allow some time for redelivery processing
        Thread.sleep(1500);

        // Confirm exactly one inbox record exists
        assertThat(consumedEventRepository.findAll().stream()
                .filter(e -> e.getConsumerName().equals(CONSUMER_GROUP) && e.getEventId().equals(eventId))
                .count()).isEqualTo(1);

        // Confirm exactly one notification job exists
        assertThat(notificationJobRepository.findAll().stream()
                .filter(j -> j.getEventId().equals(eventId))
                .count()).isEqualTo(1);
    }

    @Test
    @DisplayName("Integration C: Crash after durable commit but before acknowledgement redelivers without duplicating work")
    void testCrashAfterCommitBeforeAcknowledgementRecoversIdempotently() {
        long started = System.nanoTime();
        UUID eventId = UUID.randomUUID();
        UUID parcelId = UUID.randomUUID();
        String recipientSub = "sub-crash-recovery-" + UUID.randomUUID();

        DeliveryEventAuthorization auth = new DeliveryEventAuthorization(
                true, parcelId, eventId, recipientSub, null,
                "SWD-20261028-RECOVERY", "IN_TRANSIT", "parcel.status.changed.v1",
                2L, 2L, false, false, "AUTHORIZED");
        PermittedRecipientContact contact = new PermittedRecipientContact(
                recipientSub, "redacted@test.test", null, "Recovery Fixture",
                true, true, false, "ACTIVE", "ELIGIBLE");

        when(deliveryAuthorizationClient.authorizeEventRecipient(eq(eventId), eq(parcelId), any(), any(), anyBoolean()))
                .thenReturn(auth);
        when(accountsContactClient.resolveContact(eq(recipientSub), eq(NotificationSchedulingService.NOTIFICATION_PURPOSE), eq(NotificationChannel.EMAIL)))
                .thenReturn(Optional.of(contact));

        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                TOPIC, 0, 164L, parcelId.toString(),
                buildEventJson(eventId, parcelId, "IN_TRANSIT", "PICKED_UP", null));
        Acknowledgment firstAttempt = mock(Acknowledgment.class);
        doThrow(new IllegalStateException("injected crash before offset acknowledgement"))
                .when(firstAttempt).acknowledge();

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> lifecycleConsumer.onMessage(record, firstAttempt))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("injected crash");

        assertThat(consumedEventRepository.findAll().stream()
                .filter(e -> e.getConsumerName().equals(CONSUMER_GROUP) && e.getEventId().equals(eventId))
                .count()).isEqualTo(1);
        assertThat(notificationJobRepository.findAll().stream()
                .filter(j -> j.getEventId().equals(eventId))
                .count()).isEqualTo(1);

        Acknowledgment redelivery = mock(Acknowledgment.class);
        doNothing().when(redelivery).acknowledge();
        lifecycleConsumer.onMessage(record, redelivery);

        verify(redelivery, times(1)).acknowledge();
        verify(deliveryAuthorizationClient, times(1))
                .authorizeEventRecipient(eq(eventId), eq(parcelId), any(), any(), anyBoolean());
        assertThat(consumedEventRepository.findAll().stream()
                .filter(e -> e.getConsumerName().equals(CONSUMER_GROUP) && e.getEventId().equals(eventId))
                .count()).isEqualTo(1);
        assertThat(notificationJobRepository.findAll().stream()
                .filter(j -> j.getEventId().equals(eventId))
                .count()).isEqualTo(1);

        long elapsedMillis = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertThat(elapsedMillis).isLessThan(5_000L);
        System.out.printf("ISSUE164_COMMUNICATIONS_ACK_RECOVERY elapsedMs=%d proposedTestBudgetMs=5000%n", elapsedMillis);
    }
}
