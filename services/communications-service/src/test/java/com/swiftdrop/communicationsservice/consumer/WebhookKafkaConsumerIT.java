package com.swiftdrop.communicationsservice.consumer;

import com.swiftdrop.communicationsservice.client.AccountsMerchantEligibilityClient;
import com.swiftdrop.communicationsservice.client.AccountsRecipientContactClient;
import com.swiftdrop.communicationsservice.client.DeliveryEventAuthorization;
import com.swiftdrop.communicationsservice.client.DeliveryRecipientAuthorizationClient;
import com.swiftdrop.communicationsservice.client.PermittedRecipientContact;
import com.swiftdrop.communicationsservice.inbox.ConsumedEvent;
import com.swiftdrop.communicationsservice.inbox.ConsumedEventRepository;
import com.swiftdrop.communicationsservice.notification.NotificationChannel;
import com.swiftdrop.communicationsservice.notification.NotificationJobRepository;
import com.swiftdrop.communicationsservice.webhook.WebhookDelivery;
import com.swiftdrop.communicationsservice.webhook.WebhookDeliveryRepository;
import com.swiftdrop.communicationsservice.webhook.WebhookDeliveryStatus;
import com.swiftdrop.communicationsservice.webhook.WebhookSubscription;
import com.swiftdrop.communicationsservice.webhook.WebhookSubscriptionRepository;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EmbeddedKafka(
        partitions = 3,
        topics = {"parcel.lifecycle.v1"},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers"
)
class WebhookKafkaConsumerIT {

    private static final String TOPIC = "parcel.lifecycle.v1";
    private static final String WEBHOOK_CONSUMER_GROUP = "communications.webhooks.v1";
    private static final String NOTIFICATION_CONSUMER_GROUP = "communications.notifications.v1";

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
        registry.add("swiftdrop.kafka.consumer.webhooks.enabled", () -> "true");
        registry.add("swiftdrop.kafka.topics.parcel-lifecycle", () -> TOPIC);
        registry.add("swiftdrop.kafka.consumer.webhooks.group-id", () -> WEBHOOK_CONSUMER_GROUP);
        registry.add("spring.kafka.consumer.group-id", () -> NOTIFICATION_CONSUMER_GROUP);
    }

    @Autowired
    private EmbeddedKafkaBroker embeddedKafka;

    @Autowired
    private ConsumedEventRepository consumedEventRepository;

    @Autowired
    private WebhookSubscriptionRepository webhookSubscriptionRepository;

    @Autowired
    private WebhookDeliveryRepository webhookDeliveryRepository;

    @Autowired
    private NotificationJobRepository notificationJobRepository;

    @MockitoBean
    private DeliveryRecipientAuthorizationClient deliveryAuthorizationClient;

    @MockitoBean
    private AccountsRecipientContactClient accountsContactClient;

    @MockitoBean
    private AccountsMerchantEligibilityClient accountsMerchantEligibilityClient;

    private KafkaTemplate<String, String> producerTemplate;

    @BeforeEach
    void setUp() {
        Map<String, Object> producerProps = KafkaTestUtils.producerProps(embeddedKafka);
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producerTemplate = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(producerProps));
    }

    private String buildEventJson(UUID eventId, UUID parcelId, int version, String newStatus, String oldStatus, UUID merchantId) {
        String merchantSnippet = merchantId != null ? "\"merchantId\": \"" + merchantId + "\"," : "\"merchantId\": null,";
        String oldStatusSnippet = oldStatus != null ? "\"oldStatus\": \"" + oldStatus + "\"," : "\"oldStatus\": null,";
        return """
                {
                  "eventId": "%s",
                  "eventType": "parcel.status.changed.v1",
                  "schemaVersion": "1.0.0",
                  "aggregateId": "%s",
                  "aggregateType": "PARCEL",
                  "aggregateVersion": %d,
                  "occurredAt": "%s",
                  "correlationId": "corr-%s",
                  "producer": "delivery-operations",
                  "payload": {
                    "parcelId": "%s",
                    "trackingNumber": "SWD-20261027-W1B2",
                    %s
                    "newStatus": "%s",
                    "reason": null,
                    %s
                    "courierSub": "courier-42",
                    "cityOrZone": "Western Province Hub"
                  }
                }
                """.formatted(eventId, parcelId, version, Instant.now().toString(), eventId, parcelId, oldStatusSnippet, newStatus, merchantSnippet);
    }

    private void awaitCondition(java.util.function.BooleanSupplier condition, Duration timeout) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(100);
        }
        assertThat(condition.getAsBoolean()).as("Condition not met within timeout " + timeout).isTrue();
    }

    @Test
    @DisplayName("IT 1: Valid merchant event materializes durable WebhookDelivery job in PostgreSQL with atomic inbox commit")
    void testEndToEndMerchantEvent_MaterializesDurableJobInPostgres() throws Exception {
        UUID merchantId = UUID.randomUUID();
        UUID parcelId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        // Configure active webhook subscription for merchant
        WebhookSubscription subscription = new WebhookSubscription(
                UUID.randomUUID(),
                merchantId,
                "https://merchant-warehouse.example.com/callbacks",
                "parcel.status.changed.v1",
                "encrypted-secret-ref"
        );
        webhookSubscriptionRepository.save(subscription);

        // Publish event to Kafka
        String json = buildEventJson(eventId, parcelId, 2, "IN_TRANSIT", "PICKED_UP", merchantId);
        producerTemplate.send(TOPIC, parcelId.toString(), json).get();

        // Await consumption and persistence in PostgreSQL
        awaitCondition(() -> consumedEventRepository.existsByIdConsumerNameAndIdEventId(WEBHOOK_CONSUMER_GROUP, eventId),
                Duration.ofSeconds(10));

        // 1. Verify inbox receipt
        ConsumedEvent consumedEvent = consumedEventRepository
                .findByIdConsumerNameAndIdEventId(WEBHOOK_CONSUMER_GROUP, eventId).orElseThrow();
        assertThat(consumedEvent.getParcelId()).isEqualTo(parcelId);
        assertThat(consumedEvent.getMerchantId()).isEqualTo(merchantId);
        assertThat(consumedEvent.getVersion()).isEqualTo(2);
        assertThat(consumedEvent.getEventType()).isEqualTo("parcel.status.changed.v1");

        // 2. Verify durable webhook delivery job
        List<WebhookDelivery> deliveries = webhookDeliveryRepository.findByEventId(eventId);
        assertThat(deliveries).hasSize(1);
        WebhookDelivery job = deliveries.get(0);
        assertThat(job.getSubscriptionId()).isEqualTo(subscription.getSubscriptionId());
        assertThat(job.getEventId()).isEqualTo(eventId);
        assertThat(job.getParcelId()).isEqualTo(parcelId);
        assertThat(job.getMerchantId()).isEqualTo(merchantId);
        assertThat(job.getStateVersion()).isEqualTo(2);
        assertThat(job.getStatus()).isEqualTo(WebhookDeliveryStatus.PENDING);
        assertThat(job.getAttemptCount()).isZero();
        assertThat(job.getNextAttemptAt()).isNotNull();
        assertThat(job.getSignedPayloadDigest()).hasSize(64);
    }

    @Test
    @DisplayName("IT 2: Duplicate Kafka message redelivery does not create duplicate delivery jobs")
    void testDuplicateKafkaMessage_SafeIdempotency() throws Exception {
        UUID merchantId = UUID.randomUUID();
        UUID parcelId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        WebhookSubscription subscription = new WebhookSubscription(
                UUID.randomUUID(),
                merchantId,
                "https://merchant.example.com/api/webhooks",
                "parcel.status.changed.v1",
                "encrypted-secret"
        );
        webhookSubscriptionRepository.save(subscription);

        String json = buildEventJson(eventId, parcelId, 1, "PICKED_UP", "CREATED", merchantId);

        // Send 1st time
        producerTemplate.send(TOPIC, parcelId.toString(), json).get();
        awaitCondition(() -> consumedEventRepository.existsByIdConsumerNameAndIdEventId(WEBHOOK_CONSUMER_GROUP, eventId),
                Duration.ofSeconds(10));

        List<WebhookDelivery> initialJobs = webhookDeliveryRepository.findByEventId(eventId);
        assertThat(initialJobs).hasSize(1);
        UUID originalJobId = initialJobs.get(0).getDeliveryId();

        // Send 2nd time (duplicate delivery)
        producerTemplate.send(TOPIC, parcelId.toString(), json).get();

        // Allow some time for redelivery processing
        Thread.sleep(1500);

        // Verify exactly one inbox entry exists
        long inboxCount = consumedEventRepository.findAll().stream()
                .filter(e -> WEBHOOK_CONSUMER_GROUP.equals(e.getConsumerName()) && eventId.equals(e.getEventId()))
                .count();
        assertThat(inboxCount).isEqualTo(1);

        // Verify exactly one delivery job exists with unchanged ID
        List<WebhookDelivery> finalJobs = webhookDeliveryRepository.findByEventId(eventId);
        assertThat(finalJobs).hasSize(1);
        assertThat(finalJobs.get(0).getDeliveryId()).isEqualTo(originalJobId);
        assertThat(finalJobs.get(0).getStatus()).isEqualTo(WebhookDeliveryStatus.PENDING);
    }

    @Test
    @DisplayName("IT 3: Multiple active subscriptions for same merchant materialize one job per subscription")
    void testMultipleSubscriptions_MaterializesJobForEach() throws Exception {
        UUID merchantId = UUID.randomUUID();
        UUID parcelId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        WebhookSubscription sub1 = new WebhookSubscription(
                UUID.randomUUID(), merchantId, "https://sub1.com/hook", "parcel.status.changed.v1", "s1"
        );
        WebhookSubscription sub2 = new WebhookSubscription(
                UUID.randomUUID(), merchantId, "https://sub2.com/hook", "parcel.status.changed.v1", "s2"
        );
        webhookSubscriptionRepository.saveAll(List.of(sub1, sub2));

        String json = buildEventJson(eventId, parcelId, 2, "OUT_FOR_DELIVERY", "IN_TRANSIT", merchantId);
        producerTemplate.send(TOPIC, parcelId.toString(), json).get();

        awaitCondition(() -> consumedEventRepository.existsByIdConsumerNameAndIdEventId(WEBHOOK_CONSUMER_GROUP, eventId),
                Duration.ofSeconds(10));

        List<WebhookDelivery> jobs = webhookDeliveryRepository.findByEventId(eventId);
        assertThat(jobs).hasSize(2);
        assertThat(jobs).extracting(WebhookDelivery::getSubscriptionId)
                .containsExactlyInAnyOrder(sub1.getSubscriptionId(), sub2.getSubscriptionId());
    }

    @Test
    @DisplayName("IT 4: Direct retail parcel (merchantId null) creates inbox record without webhook delivery jobs")
    void testDirectRetailParcel_InboxRecordedZeroJobs() throws Exception {
        UUID parcelId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        // null merchantId = direct retail parcel
        String json = buildEventJson(eventId, parcelId, 1, "CREATED", null, null);
        producerTemplate.send(TOPIC, parcelId.toString(), json).get();

        awaitCondition(() -> consumedEventRepository.existsByIdConsumerNameAndIdEventId(WEBHOOK_CONSUMER_GROUP, eventId),
                Duration.ofSeconds(10));

        ConsumedEvent inbox = consumedEventRepository
                .findByIdConsumerNameAndIdEventId(WEBHOOK_CONSUMER_GROUP, eventId).orElseThrow();
        assertThat(inbox.getMerchantId()).isNull();

        List<WebhookDelivery> jobs = webhookDeliveryRepository.findByEventId(eventId);
        assertThat(jobs).isEmpty();
    }

    @Test
    @DisplayName("IT 5: Disabled subscription (active = false) does not create webhook delivery jobs")
    void testDisabledSubscription_ZeroJobs() throws Exception {
        UUID merchantId = UUID.randomUUID();
        UUID parcelId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        WebhookSubscription sub = new WebhookSubscription(
                UUID.randomUUID(), merchantId, "https://disabled.com/hook", "parcel.status.changed.v1", "s"
        );
        sub.setActive(false);
        webhookSubscriptionRepository.save(sub);

        String json = buildEventJson(eventId, parcelId, 1, "PICKED_UP", "CREATED", merchantId);
        producerTemplate.send(TOPIC, parcelId.toString(), json).get();

        awaitCondition(() -> consumedEventRepository.existsByIdConsumerNameAndIdEventId(WEBHOOK_CONSUMER_GROUP, eventId),
                Duration.ofSeconds(10));

        List<WebhookDelivery> jobs = webhookDeliveryRepository.findByEventId(eventId);
        assertThat(jobs).isEmpty();
    }

    @Test
    @DisplayName("IT 6: Reordered event with stale state version is skipped and does not overwrite newer job")
    void testStaleEvent_SkippedSafely() throws Exception {
        UUID merchantId = UUID.randomUUID();
        UUID parcelId = UUID.randomUUID();
        UUID eventV3 = UUID.randomUUID();
        UUID eventV2 = UUID.randomUUID();

        WebhookSubscription sub = new WebhookSubscription(
                UUID.randomUUID(), merchantId, "https://ordering.com/hook", "parcel.status.changed.v1", "s"
        );
        webhookSubscriptionRepository.save(sub);

        // 1. Deliver version 3 first (OUT_FOR_DELIVERY)
        String jsonV3 = buildEventJson(eventV3, parcelId, 3, "OUT_FOR_DELIVERY", "IN_TRANSIT", merchantId);
        producerTemplate.send(TOPIC, parcelId.toString(), jsonV3).get();

        awaitCondition(() -> consumedEventRepository.existsByIdConsumerNameAndIdEventId(WEBHOOK_CONSUMER_GROUP, eventV3),
                Duration.ofSeconds(10));

        List<WebhookDelivery> jobsAfterV3 = webhookDeliveryRepository.findByParcelIdOrderByCreatedAtDesc(parcelId);
        assertThat(jobsAfterV3).hasSize(1);
        assertThat(jobsAfterV3.get(0).getStateVersion()).isEqualTo(3);

        // 2. Deliver out-of-order version 2 later (IN_TRANSIT)
        String jsonV2 = buildEventJson(eventV2, parcelId, 2, "IN_TRANSIT", "PICKED_UP", merchantId);
        producerTemplate.send(TOPIC, parcelId.toString(), jsonV2).get();

        awaitCondition(() -> consumedEventRepository.existsByIdConsumerNameAndIdEventId(WEBHOOK_CONSUMER_GROUP, eventV2),
                Duration.ofSeconds(10));

        // Total delivery jobs for this parcel must still be 1 (stale version 2 skipped)
        List<WebhookDelivery> finalJobs = webhookDeliveryRepository.findByParcelIdOrderByCreatedAtDesc(parcelId);
        assertThat(finalJobs).hasSize(1);
        assertThat(finalJobs.get(0).getStateVersion()).isEqualTo(3);
    }

    @Test
    @DisplayName("IT 7: Webhook consumer and Notification consumer operate independently with isolated inbox receipts")
    void testDualConsumerGroupIndependence() throws Exception {
        UUID merchantId = UUID.randomUUID();
        UUID parcelId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        String recipientSub = "sub-buyer-" + UUID.randomUUID();

        // 1. Setup webhook subscription
        WebhookSubscription sub = new WebhookSubscription(
                UUID.randomUUID(), merchantId, "https://multi-consumer.com/hook", "parcel.status.changed.v1", "s"
        );
        webhookSubscriptionRepository.save(sub);

        // 2. Setup notification mocks
        DeliveryEventAuthorization auth = new DeliveryEventAuthorization(
                true, parcelId, eventId, recipientSub, merchantId, "SWD-20261027-A1B2",
                "DELIVERED", "parcel.status.changed.v1", 4L, 4L, true, false, "AUTHORIZED"
        );
        PermittedRecipientContact contact = new PermittedRecipientContact(
                recipientSub, "buyer@test.test", null, "Buyer Name", true, true, false, "ACTIVE", "ELIGIBLE"
        );
        when(deliveryAuthorizationClient.authorizeEventRecipient(eq(eventId), eq(parcelId), any(), any(), anyBoolean()))
                .thenReturn(auth);
        when(accountsContactClient.resolveContact(eq(recipientSub), any(), eq(NotificationChannel.EMAIL)))
                .thenReturn(Optional.of(contact));

        // 3. Publish single event to parcel.lifecycle.v1
        String json = buildEventJson(eventId, parcelId, 4, "DELIVERED", "OUT_FOR_DELIVERY", merchantId);
        producerTemplate.send(TOPIC, parcelId.toString(), json).get();

        // 4. Await both consumer groups committing their respective inbox entries
        awaitCondition(() -> consumedEventRepository.existsByIdConsumerNameAndIdEventId(WEBHOOK_CONSUMER_GROUP, eventId),
                Duration.ofSeconds(10));
        awaitCondition(() -> consumedEventRepository.existsByIdConsumerNameAndIdEventId(NOTIFICATION_CONSUMER_GROUP, eventId),
                Duration.ofSeconds(10));

        // Verify independent inbox receipts
        assertThat(consumedEventRepository.existsByIdConsumerNameAndIdEventId(WEBHOOK_CONSUMER_GROUP, eventId)).isTrue();
        assertThat(consumedEventRepository.existsByIdConsumerNameAndIdEventId(NOTIFICATION_CONSUMER_GROUP, eventId)).isTrue();

        // Verify both domain jobs exist
        assertThat(webhookDeliveryRepository.findByEventId(eventId)).hasSize(1);
        assertThat(notificationJobRepository.findByEventIdAndRecipientSubAndChannel(
                eventId, recipientSub, NotificationChannel.EMAIL)).isPresent();
    }
}
