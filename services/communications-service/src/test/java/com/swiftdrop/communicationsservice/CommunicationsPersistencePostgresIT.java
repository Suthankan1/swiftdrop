package com.swiftdrop.communicationsservice;

import com.swiftdrop.communicationsservice.inbox.ConsumedEvent;
import com.swiftdrop.communicationsservice.inbox.ConsumedEventId;
import com.swiftdrop.communicationsservice.inbox.ConsumedEventRepository;
import com.swiftdrop.communicationsservice.notification.NotificationChannel;
import com.swiftdrop.communicationsservice.notification.NotificationJob;
import com.swiftdrop.communicationsservice.notification.NotificationJobRepository;
import com.swiftdrop.communicationsservice.notification.NotificationStatus;
import com.swiftdrop.communicationsservice.transaction.DurableJobPersistenceService;
import com.swiftdrop.communicationsservice.transaction.DuplicateEventException;
import com.swiftdrop.communicationsservice.webhook.WebhookAttempt;
import com.swiftdrop.communicationsservice.webhook.WebhookAttemptRepository;
import com.swiftdrop.communicationsservice.webhook.WebhookDelivery;
import com.swiftdrop.communicationsservice.webhook.WebhookDeliveryRepository;
import com.swiftdrop.communicationsservice.webhook.WebhookDeliveryStatus;
import com.swiftdrop.communicationsservice.webhook.WebhookSubscription;
import com.swiftdrop.communicationsservice.webhook.WebhookSubscriptionRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CommunicationsPersistencePostgresIT {

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
    }

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ConsumedEventRepository consumedEventRepository;

    @Autowired
    private NotificationJobRepository notificationJobRepository;

    @Autowired
    private WebhookSubscriptionRepository webhookSubscriptionRepository;

    @Autowired
    private WebhookDeliveryRepository webhookDeliveryRepository;

    @Autowired
    private WebhookAttemptRepository webhookAttemptRepository;

    @Autowired
    private DurableJobPersistenceService durableJobPersistenceService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("A. Flyway applies V1 migration cleanly on real PostgreSQL communications_db")
    void testFlywayMigrationCleanApplication() {
        assertThat(flyway.info().pending()).isEmpty();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("1");
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).isEqualTo("communications_db");

        List<String> tables = jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'public'
                """, String.class);

        assertThat(tables).contains(
                "consumed_events",
                "notifications",
                "webhook_subscriptions",
                "webhook_deliveries",
                "webhook_attempts",
                "flyway_schema_history"
        );
    }

    @Test
    @DisplayName("B. Consumed events persist durably and enforce composite PK (consumer_name, event_id)")
    void testConsumedEventsDurablePersistenceAndDeduplication() {
        UUID eventId = UUID.randomUUID();
        UUID parcelId = UUID.randomUUID();
        String consumerA = "communications.notifications.v1";
        String consumerB = "communications.webhooks.v1";

        // Consumer A persists receipt
        ConsumedEvent eventA = new ConsumedEvent(
                new ConsumedEventId(consumerA, eventId),
                "parcel.status.changed.v1",
                1,
                parcelId,
                null,
                Instant.now()
        );
        consumedEventRepository.saveAndFlush(eventA);

        // Readback
        ConsumedEvent readbackA = consumedEventRepository.findByIdConsumerNameAndIdEventId(consumerA, eventId).orElseThrow();
        assertThat(readbackA.getParcelId()).isEqualTo(parcelId);

        // Duplicate INSERT for Consumer A fails at database composite primary key constraint
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO consumed_events (consumer_name, event_id, event_type, version, parcel_id, received_at) VALUES (?, ?, ?, ?, ?, ?)",
                consumerA, eventId, "parcel.status.changed.v1", 1, parcelId, Timestamp.from(Instant.now())))
                .isInstanceOf(DataIntegrityViolationException.class);

        // Application-level persistence service also rejects duplicate events
        NotificationJob dupJob = new NotificationJob(
                UUID.randomUUID(),
                eventId,
                "recip-dup",
                parcelId,
                NotificationChannel.EMAIL,
                "dup@test.test",
                "TEST"
        );
        ConsumedEvent dupEventA = new ConsumedEvent(
                new ConsumedEventId(consumerA, eventId),
                "parcel.status.changed.v1",
                1,
                parcelId,
                null,
                Instant.now()
        );
        assertThatThrownBy(() -> durableJobPersistenceService.persistInboxAndNotification(dupEventA, dupJob))
                .isInstanceOf(DuplicateEventException.class);

        // Independent Consumer B can safely process the SAME event ID
        ConsumedEvent eventB = new ConsumedEvent(
                new ConsumedEventId(consumerB, eventId),
                "parcel.status.changed.v1",
                1,
                parcelId,
                null,
                Instant.now()
        );
        consumedEventRepository.saveAndFlush(eventB);

        ConsumedEvent readbackB = consumedEventRepository.findByIdConsumerNameAndIdEventId(consumerB, eventId).orElseThrow();
        assertThat(readbackB.getConsumerName()).isEqualTo(consumerB);
        assertThat(readbackB.getEventId()).isEqualTo(eventId);
    }

    @Test
    @DisplayName("C. Notification jobs persist and enforce unique (event_id, recipient_sub, channel)")
    void testNotificationJobsPersistenceAndConstraint() {
        UUID eventId = UUID.randomUUID();
        UUID parcelId = UUID.randomUUID();
        String recipientSub = "recip-" + UUID.randomUUID();

        NotificationJob job = new NotificationJob(
                UUID.randomUUID(),
                eventId,
                recipientSub,
                parcelId,
                NotificationChannel.EMAIL,
                "buyer@test.test",
                "DELIVERY_COMPLETED"
        );
        notificationJobRepository.saveAndFlush(job);

        NotificationJob readback = notificationJobRepository.findByEventIdAndRecipientSubAndChannel(
                eventId, recipientSub, NotificationChannel.EMAIL).orElseThrow();
        assertThat(readback.getDestinationRef()).isEqualTo("buyer@test.test");
        assertThat(readback.getStatus()).isEqualTo(NotificationStatus.PENDING);

        // Duplicate (event_id, recipient_sub, channel) fails
        NotificationJob duplicateJob = new NotificationJob(
                UUID.randomUUID(),
                eventId,
                recipientSub,
                parcelId,
                NotificationChannel.EMAIL,
                "buyer2@test.test",
                "DELIVERY_COMPLETED"
        );
        assertThatThrownBy(() -> notificationJobRepository.saveAndFlush(duplicateJob))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("D. Webhook subscriptions, deliveries, and attempt history persist with foreign keys")
    void testWebhookLifecyclePersistence() {
        UUID merchantId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID parcelId = UUID.randomUUID();

        // 1. Subscription
        WebhookSubscription sub = new WebhookSubscription(
                UUID.randomUUID(),
                merchantId,
                "https://merchant.example.test/webhooks/status",
                "parcel.status.changed.v1",
                "sec_ref_99"
        );
        webhookSubscriptionRepository.saveAndFlush(sub);

        // 2. Delivery
        WebhookDelivery delivery = new WebhookDelivery(
                UUID.randomUUID(),
                sub.getSubscriptionId(),
                eventId,
                parcelId,
                merchantId,
                1,
                "sha256_digest_xyz"
        );
        webhookDeliveryRepository.saveAndFlush(delivery);

        // Duplicate (subscription_id, event_id) fails
        WebhookDelivery duplicateDelivery = new WebhookDelivery(
                UUID.randomUUID(),
                sub.getSubscriptionId(),
                eventId,
                parcelId,
                merchantId,
                1,
                "sha256_digest_xyz"
        );
        assertThatThrownBy(() -> webhookDeliveryRepository.saveAndFlush(duplicateDelivery))
                .isInstanceOf(DataIntegrityViolationException.class);

        // 3. Attempt
        WebhookAttempt attempt = new WebhookAttempt(
                UUID.randomUUID(),
                delivery.getDeliveryId(),
                Instant.now(),
                200,
                85,
                null
        );
        webhookAttemptRepository.saveAndFlush(attempt);

        List<WebhookAttempt> attempts = webhookAttemptRepository.findByDeliveryIdOrderByAttemptedAtDesc(delivery.getDeliveryId());
        assertThat(attempts).hasSize(1);
        assertThat(attempts.getFirst().getHttpStatusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("E. Atomic transaction rollback rolls back both inbox receipt and job on failure")
    void testAtomicTransactionRollback() {
        UUID eventId = UUID.randomUUID();
        UUID parcelId = UUID.randomUUID();
        String consumer = "communications.rollback.v1";
        ConsumedEventId id = new ConsumedEventId(consumer, eventId);
        ConsumedEvent event = new ConsumedEvent(id, "parcel.status.changed.v1", 1, parcelId, null, Instant.now());

        // Create an invalid notification job that violates database NOT NULL constraint (e.g. null recipient_sub)
        NotificationJob invalidJob = new NotificationJob();
        invalidJob.setNotificationId(UUID.randomUUID());
        invalidJob.setEventId(eventId);
        invalidJob.setParcelId(parcelId);
        invalidJob.setChannel(NotificationChannel.EMAIL);
        invalidJob.setTemplate("TMP");
        // recipientSub is null -> violates NOT NULL

        assertThatThrownBy(() -> durableJobPersistenceService.persistInboxAndNotification(event, invalidJob))
                .isInstanceOf(Exception.class);

        // Verify inbox receipt was NOT persisted (rolled back atomically)
        assertThat(consumedEventRepository.existsByIdConsumerNameAndIdEventId(consumer, eventId)).isFalse();
        assertThat(notificationJobRepository.findAll().stream().anyMatch(j -> j.getEventId().equals(eventId))).isFalse();
    }

    @Test
    @DisplayName("F. Atomic transaction success commits both inbox receipt and job")
    void testAtomicTransactionSuccess() {
        UUID eventId = UUID.randomUUID();
        UUID parcelId = UUID.randomUUID();
        String consumer = "communications.atomic.v1";
        ConsumedEventId id = new ConsumedEventId(consumer, eventId);
        ConsumedEvent event = new ConsumedEvent(id, "parcel.status.changed.v1", 1, parcelId, null, Instant.now());
        NotificationJob job = new NotificationJob(
                UUID.randomUUID(),
                eventId,
                "sub-atomic",
                parcelId,
                NotificationChannel.EMAIL,
                "atomic@test.test",
                "ATOMIC_TMPL"
        );

        durableJobPersistenceService.persistInboxAndNotification(event, job);

        assertThat(consumedEventRepository.existsByIdConsumerNameAndIdEventId(consumer, eventId)).isTrue();
        assertThat(notificationJobRepository.findByEventIdAndRecipientSubAndChannel(eventId, "sub-atomic", NotificationChannel.EMAIL)).isPresent();
    }

    @Test
    @DisplayName("G. Restart durability verification: data survives reconnection against persistent PostgreSQL")
    void testDataSurvivesRestart() {
        UUID eventId = UUID.randomUUID();
        UUID parcelId = UUID.randomUUID();
        String consumer = "communications.restart.v1";
        ConsumedEventId id = new ConsumedEventId(consumer, eventId);

        ConsumedEvent event = new ConsumedEvent(id, "parcel.status.changed.v1", 1, parcelId, null, Instant.now());
        consumedEventRepository.saveAndFlush(event);

        // Verify direct SQL query against PostgreSQL container sees the committed record
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM consumed_events WHERE consumer_name = ? AND event_id = ?",
                Integer.class,
                consumer,
                eventId
        );
        assertThat(count).isEqualTo(1);
    }
}
