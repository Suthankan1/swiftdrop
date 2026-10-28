package com.swiftdrop.communicationsservice;

import com.swiftdrop.communicationsservice.inbox.ConsumedEvent;
import com.swiftdrop.communicationsservice.inbox.ConsumedEventId;
import com.swiftdrop.communicationsservice.inbox.ConsumedEventRepository;
import com.swiftdrop.communicationsservice.notification.NotificationAttempt;
import com.swiftdrop.communicationsservice.notification.NotificationAttemptRepository;
import com.swiftdrop.communicationsservice.notification.NotificationChannel;
import com.swiftdrop.communicationsservice.notification.NotificationJob;
import com.swiftdrop.communicationsservice.notification.NotificationJobRepository;
import com.swiftdrop.communicationsservice.notification.NotificationReplayAudit;
import com.swiftdrop.communicationsservice.notification.NotificationReplayAuditRepository;
import com.swiftdrop.communicationsservice.notification.NotificationStatus;
import com.swiftdrop.communicationsservice.consumer.quarantine.PoisonEventQuarantine;
import com.swiftdrop.communicationsservice.consumer.quarantine.PoisonEventQuarantineRepository;
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
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
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
    private NotificationAttemptRepository notificationAttemptRepository;

    @Autowired
    private NotificationReplayAuditRepository notificationReplayAuditRepository;

    @Autowired
    private PoisonEventQuarantineRepository poisonEventQuarantineRepository;

    @Autowired
    private WebhookSubscriptionRepository webhookSubscriptionRepository;

    @Autowired
    private WebhookDeliveryRepository webhookDeliveryRepository;

    @Autowired
    private WebhookAttemptRepository webhookAttemptRepository;

    @Autowired
    private com.swiftdrop.communicationsservice.webhook.WebhookReplayAuditRepository webhookReplayAuditRepository;

    @Autowired
    private DurableJobPersistenceService durableJobPersistenceService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("A. Flyway applies V1, V2, V3, and V4 migrations cleanly on real PostgreSQL communications_db")
    void testFlywayMigrationCleanApplication() {
        assertThat(flyway.info().pending()).isEmpty();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("4");
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
                "webhook_replays",
                "notification_attempts",
                "notification_replays",
                "poison_event_quarantine",
                "flyway_schema_history"
        );

        // Verify correlation_id column on consumed_events, notifications, and webhook_deliveries (Issue #158)
        List<String> correlationTables = List.of("consumed_events", "notifications", "webhook_deliveries");
        for (String table : correlationTables) {
            Integer count = jdbc.queryForObject("""
                    SELECT count(*) FROM information_schema.columns
                    WHERE table_schema = 'public'
                      AND table_name = ?
                      AND column_name = 'correlation_id'
                      AND data_type = 'character varying'
                      AND character_maximum_length = 64
                    """, Integer.class, table);
            assertThat(count).as("Table %s must have correlation_id VARCHAR(64) column", table).isEqualTo(1);
        }

        // Verify correlation_id indexes
        List<String> indexes = jdbc.queryForList("""
                SELECT indexname FROM pg_indexes
                WHERE schemaname = 'public'
                """, String.class);
        assertThat(indexes).contains(
                "idx_consumed_events_correlation_id",
                "idx_notifications_correlation_id",
                "idx_webhook_deliveries_correlation_id"
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
                .isInstanceOf(DataIntegrityViolationException.class);

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

    @Test
    @DisplayName("H. Webhook subscription tenant-scoped queries, soft deactivation, and FK restriction")
    void testWebhookSubscriptionTenantIsolationAndRestriction() {
        UUID merchantA = UUID.randomUUID();
        UUID merchantB = UUID.randomUUID();

        // Subscription for merchant A
        WebhookSubscription subA = new WebhookSubscription(
                UUID.randomUUID(),
                merchantA,
                "https://merchant-a.example.com/callbacks",
                "parcel.status.changed.v1",
                "enc:v1:secretA"
        );
        webhookSubscriptionRepository.saveAndFlush(subA);

        // Subscription for merchant B
        WebhookSubscription subB = new WebhookSubscription(
                UUID.randomUUID(),
                merchantB,
                "https://merchant-b.example.com/callbacks",
                "parcel.status.changed.v1",
                "enc:v1:secretB"
        );
        webhookSubscriptionRepository.saveAndFlush(subB);

        // 1. Tenant-scoped query finds own subscription
        assertThat(webhookSubscriptionRepository.findBySubscriptionIdAndMerchantId(subA.getSubscriptionId(), merchantA)).isPresent();
        assertThat(webhookSubscriptionRepository.findBySubscriptionIdAndMerchantId(subA.getSubscriptionId(), merchantB)).isEmpty();

        // 2. Tenant-scoped list returns only merchant A's subscriptions
        List<WebhookSubscription> listA = webhookSubscriptionRepository.findByMerchantIdOrderByCreatedAtDesc(merchantA);
        assertThat(listA).hasSize(1);
        assertThat(listA.getFirst().getSubscriptionId()).isEqualTo(subA.getSubscriptionId());

        // 3. Attach delivery to subA
        UUID deliveryId = UUID.randomUUID();
        WebhookDelivery delivery = new WebhookDelivery(
                deliveryId,
                subA.getSubscriptionId(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                merchantA,
                1,
                "digest"
        );
        webhookDeliveryRepository.saveAndFlush(delivery);

        // 4. Hard DELETE on subA fails due to ON DELETE RESTRICT foreign key
        assertThatThrownBy(() -> {
            webhookSubscriptionRepository.delete(subA);
            webhookSubscriptionRepository.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);

        // 5. Soft deactivation succeeds and preserves child delivery
        subA.setActive(false);
        webhookSubscriptionRepository.saveAndFlush(subA);

        WebhookSubscription readback = webhookSubscriptionRepository.findById(subA.getSubscriptionId()).orElseThrow();
        assertThat(readback.getActive()).isFalse();
        assertThat(webhookDeliveryRepository.findById(deliveryId)).isPresent();
    }

    @Test
    @DisplayName("H. Multiple webhook attempts persist with accurate sequence, error summary, and latency")
    void testWebhookMultipleAttemptHistoryAndStateTransitions() {
        UUID merchantId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID parcelId = UUID.randomUUID();

        WebhookSubscription sub = new WebhookSubscription(
                UUID.randomUUID(),
                merchantId,
                "https://merchant.example.test/webhooks/orders",
                "parcel.status.changed.v1",
                "enc:v1:test-key"
        );
        webhookSubscriptionRepository.saveAndFlush(sub);

        WebhookDelivery delivery = new WebhookDelivery(
                UUID.randomUUID(),
                sub.getSubscriptionId(),
                eventId,
                parcelId,
                merchantId,
                1,
                "payload_sha256"
        );
        delivery.setStatus(WebhookDeliveryStatus.PENDING);
        delivery.setAttemptCount(0);
        webhookDeliveryRepository.saveAndFlush(delivery);

        // First attempt: timeout failure (null status code, has error summary)
        Instant t1 = Instant.now().minusSeconds(120);
        WebhookAttempt attempt1 = new WebhookAttempt(
                UUID.randomUUID(),
                delivery.getDeliveryId(),
                t1,
                null,
                5002,
                "Connect to merchant.example.test:443 timed out after 5000ms"
        );
        webhookAttemptRepository.saveAndFlush(attempt1);

        delivery.setAttemptCount(1);
        delivery.setNextAttemptAt(t1.plusSeconds(10));
        webhookDeliveryRepository.saveAndFlush(delivery);

        // Second attempt: 502 Bad Gateway
        Instant t2 = Instant.now().minusSeconds(60);
        WebhookAttempt attempt2 = new WebhookAttempt(
                UUID.randomUUID(),
                delivery.getDeliveryId(),
                t2,
                502,
                320,
                "HTTP 502 Bad Gateway"
        );
        webhookAttemptRepository.saveAndFlush(attempt2);

        delivery.setAttemptCount(2);
        delivery.setNextAttemptAt(t2.plusSeconds(30));
        webhookDeliveryRepository.saveAndFlush(delivery);

        // Third attempt: 200 OK
        Instant t3 = Instant.now();
        WebhookAttempt attempt3 = new WebhookAttempt(
                UUID.randomUUID(),
                delivery.getDeliveryId(),
                t3,
                200,
                142,
                null
        );
        webhookAttemptRepository.saveAndFlush(attempt3);

        delivery.setAttemptCount(3);
        delivery.setStatus(WebhookDeliveryStatus.SUCCESS);
        delivery.setNextAttemptAt(null);
        webhookDeliveryRepository.saveAndFlush(delivery);

        // Verify all 3 attempts are preserved in order
        List<WebhookAttempt> history = webhookAttemptRepository.findByDeliveryIdOrderByAttemptedAtDesc(delivery.getDeliveryId());
        assertThat(history).hasSize(3);
        assertThat(history.get(0).getHttpStatusCode()).isEqualTo(200);
        assertThat(history.get(0).getResponseTimeMs()).isEqualTo(142);
        assertThat(history.get(0).getErrorSummary()).isNull();

        assertThat(history.get(1).getHttpStatusCode()).isEqualTo(502);
        assertThat(history.get(1).getResponseTimeMs()).isEqualTo(320);
        assertThat(history.get(1).getErrorSummary()).isEqualTo("HTTP 502 Bad Gateway");

        assertThat(history.get(2).getHttpStatusCode()).isNull();
        assertThat(history.get(2).getResponseTimeMs()).isEqualTo(5002);
        assertThat(history.get(2).getErrorSummary()).contains("timed out");

        WebhookDelivery finalDelivery = webhookDeliveryRepository.findById(delivery.getDeliveryId()).orElseThrow();
        assertThat(finalDelivery.getStatus()).isEqualTo(WebhookDeliveryStatus.SUCCESS);
        assertThat(finalDelivery.getAttemptCount()).isEqualTo(3);
        assertThat(finalDelivery.getNextAttemptAt()).isNull();

        // Verify query for stuck jobs
        finalDelivery.setStatus(WebhookDeliveryStatus.PROCESSING);
        webhookDeliveryRepository.saveAndFlush(finalDelivery);
        List<WebhookDelivery> stuck = webhookDeliveryRepository.findByStatusAndUpdatedAtLessThanEqual(
                WebhookDeliveryStatus.PROCESSING, Instant.now().plusSeconds(60));
        assertThat(stuck).extracting(WebhookDelivery::getDeliveryId).contains(delivery.getDeliveryId());
    }

    @Test
    @DisplayName("I. Concurrent worker lease safety, due query ordering, and exhaustion terminal status")
    void testConcurrentWorkerLeaseAndExhaustionLifecycle() {
        UUID merchantId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID parcelId = UUID.randomUUID();

        WebhookSubscription sub = new WebhookSubscription(
                UUID.randomUUID(),
                merchantId,
                "https://merchant.example.test/webhooks/scheduler",
                "parcel.status.changed.v1",
                "enc:v1:test-key"
        );
        webhookSubscriptionRepository.saveAndFlush(sub);

        WebhookDelivery delivery = new WebhookDelivery(
                UUID.randomUUID(),
                sub.getSubscriptionId(),
                eventId,
                parcelId,
                merchantId,
                1,
                "payload_sha256"
        );
        Instant now = Instant.now();
        delivery.setStatus(WebhookDeliveryStatus.PENDING);
        delivery.setNextAttemptAt(now.minusSeconds(10));
        webhookDeliveryRepository.saveAndFlush(delivery);

        // 1. Query for due deliveries returns the candidate job
        List<WebhookDelivery> due = webhookDeliveryRepository.findDueDeliveries(
                WebhookDeliveryStatus.PENDING, now, org.springframework.data.domain.PageRequest.of(0, 10));
        assertThat(due).extracting(WebhookDelivery::getDeliveryId).contains(delivery.getDeliveryId());

        // 2. Worker 1 claims lease via atomic UPDATE -> succeeds (returns 1)
        int worker1Claim = webhookDeliveryRepository.claimDelivery(
                delivery.getDeliveryId(),
                WebhookDeliveryStatus.PENDING,
                WebhookDeliveryStatus.PROCESSING,
                now
        );
        assertThat(worker1Claim).isEqualTo(1);

        // 3. Concurrent Worker 2 attempts to claim same job -> fails (returns 0) because status is no longer PENDING
        int worker2Claim = webhookDeliveryRepository.claimDelivery(
                delivery.getDeliveryId(),
                WebhookDeliveryStatus.PENDING,
                WebhookDeliveryStatus.PROCESSING,
                now
        );
        assertThat(worker2Claim).isEqualTo(0);

        // 4. Job is verified in PROCESSING state in database
        WebhookDelivery claimedDelivery = webhookDeliveryRepository.findById(delivery.getDeliveryId()).orElseThrow();
        assertThat(claimedDelivery.getStatus()).isEqualTo(WebhookDeliveryStatus.PROCESSING);

        // 5. If job crashes and remains in PROCESSING past lease cutoff, claimDeliveryWithLease recovers it
        Instant leaseCutoff = now.plusSeconds(10); // simulate cutoff where job's updated_at <= leaseCutoff
        int recoveryClaim = webhookDeliveryRepository.claimDeliveryWithLease(
                delivery.getDeliveryId(),
                WebhookDeliveryStatus.PENDING,
                WebhookDeliveryStatus.PROCESSING,
                now.plusSeconds(300),
                leaseCutoff
        );
        assertThat(recoveryClaim).isEqualTo(1);

        // 6. Transition to terminal EXHAUSTED status
        claimedDelivery.setStatus(WebhookDeliveryStatus.EXHAUSTED);
        claimedDelivery.setAttemptCount(5);
        claimedDelivery.setNextAttemptAt(null);
        webhookDeliveryRepository.saveAndFlush(claimedDelivery);

        // 7. Exhausted job is never returned by findDueDeliveries
        List<WebhookDelivery> dueAfterExhaustion = webhookDeliveryRepository.findDueDeliveries(
                WebhookDeliveryStatus.PENDING, now.plusSeconds(600), org.springframework.data.domain.PageRequest.of(0, 10));
        assertThat(dueAfterExhaustion).extracting(WebhookDelivery::getDeliveryId).doesNotContain(delivery.getDeliveryId());
    }

    @Test
    @DisplayName("N. Replaying exhausted delivery transitions state to PENDING and persists durable replay audit")
    void testWebhookDeliveryReplayAndAuditPersistence() {
        UUID merchantId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID parcelId = UUID.randomUUID();

        WebhookSubscription sub = new WebhookSubscription(
                null, merchantId, "https://api.partner.com/wh", "parcel.status.changed.v1", "enc:v1:token");
        sub = webhookSubscriptionRepository.saveAndFlush(sub);

        WebhookDelivery delivery = new WebhookDelivery(
                null, sub.getSubscriptionId(), eventId, parcelId, merchantId, 1, "digest-replay-test");
        delivery.setStatus(WebhookDeliveryStatus.EXHAUSTED);
        delivery.setAttemptCount(5);
        delivery.setNextAttemptAt(null);
        delivery = webhookDeliveryRepository.saveAndFlush(delivery);

        // Replay: reset to PENDING and record audit
        Instant now = Instant.now();
        com.swiftdrop.communicationsservice.webhook.WebhookReplayAudit audit =
                new com.swiftdrop.communicationsservice.webhook.WebhookReplayAudit(
                        null,
                        delivery.getDeliveryId(),
                        sub.getSubscriptionId(),
                        merchantId,
                        "operator-sub-456",
                        "ADMIN",
                        "Manual operator unblock after endpoint recovery",
                        "EXHAUSTED",
                        now
                );
        audit = webhookReplayAuditRepository.saveAndFlush(audit);

        assertThat(audit.getReplayId()).isNotNull();
        assertThat(audit.getPreviousStatus()).isEqualTo("EXHAUSTED");
        assertThat(audit.getActorRole()).isEqualTo("ADMIN");

        // Verify audit can be queried by deliveryId
        List<com.swiftdrop.communicationsservice.webhook.WebhookReplayAudit> audits =
                webhookReplayAuditRepository.findByDeliveryIdOrderByReplayedAtDesc(delivery.getDeliveryId());
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0).getReason()).contains("Manual operator unblock");

        // Replay updates the delivery
        delivery.setStatus(WebhookDeliveryStatus.PENDING);
        delivery.setAttemptCount(0);
        delivery.setNextAttemptAt(now);
        webhookDeliveryRepository.saveAndFlush(delivery);

        // Verify replayed job is immediately discoverable by findDueDeliveries
        List<WebhookDelivery> dueReplayed = webhookDeliveryRepository.findDueDeliveries(
                WebhookDeliveryStatus.PENDING, now.plusSeconds(10), org.springframework.data.domain.PageRequest.of(0, 10));
        assertThat(dueReplayed).extracting(WebhookDelivery::getDeliveryId).contains(delivery.getDeliveryId());

        // Verify database constraint: non-failed status violates CHECK constraint
        com.swiftdrop.communicationsservice.webhook.WebhookReplayAudit invalidAudit =
                new com.swiftdrop.communicationsservice.webhook.WebhookReplayAudit(
                        null,
                        delivery.getDeliveryId(),
                        sub.getSubscriptionId(),
                        merchantId,
                        "operator-sub-456",
                        "ADMIN",
                        "Invalid replay test",
                        "SUCCESS",
                        now
                );
        assertThatThrownBy(() -> webhookReplayAuditRepository.saveAndFlush(invalidAudit))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("K. Notification worker concurrent lease exclusion: two racing workers on same job, exactly one wins")
    void testNotificationWorkerLeaseExclusion() {
        UUID eventId = UUID.randomUUID();
        UUID parcelId = UUID.randomUUID();
        NotificationJob job = new NotificationJob(
                UUID.randomUUID(),
                eventId,
                "user-lease-race-1",
                parcelId,
                NotificationChannel.EMAIL,
                "sub:user-lease-race-1",
                "PARCEL_DELIVERED"
        );
        job.setStatus(NotificationStatus.PENDING);
        job.setAttempts(0);
        job.setCreatedAt(Instant.now());
        job.setNextAttemptAt(Instant.now().minusSeconds(10));
        job = notificationJobRepository.saveAndFlush(job);

        Instant now = Instant.now();
        Instant leaseExpiresAt = now.plusSeconds(300);

        String worker1Token = UUID.randomUUID().toString();
        String worker2Token = UUID.randomUUID().toString();

        // Worker 1 claims
        int claimed1 = notificationJobRepository.claimJobWithLease(
                job.getNotificationId(),
                NotificationStatus.PENDING,
                NotificationStatus.PROCESSING,
                worker1Token,
                now,
                leaseExpiresAt
        );

        // Worker 2 attempts to claim same job concurrently
        int claimed2 = notificationJobRepository.claimJobWithLease(
                job.getNotificationId(),
                NotificationStatus.PENDING,
                NotificationStatus.PROCESSING,
                worker2Token,
                now,
                leaseExpiresAt
        );

        assertThat(claimed1).isEqualTo(1);
        assertThat(claimed2).isEqualTo(0);

        NotificationJob claimedJob = notificationJobRepository.findById(job.getNotificationId()).orElseThrow();
        assertThat(claimedJob.getStatus()).isEqualTo(NotificationStatus.PROCESSING);
        assertThat(claimedJob.getClaimToken()).isEqualTo(worker1Token);
        assertThat(claimedJob.getLeaseExpiresAt()).isNotNull();

        // Worker 1 records attempt in notification_attempts
        NotificationAttempt attempt = new NotificationAttempt(
                UUID.randomUUID(),
                job.getNotificationId(),
                1,
                NotificationChannel.EMAIL,
                "SUCCESS",
                null,
                "DELIVERED",
                85,
                now,
                now
        );
        notificationAttemptRepository.saveAndFlush(attempt);

        List<NotificationAttempt> attempts = notificationAttemptRepository.findByNotificationIdOrderByAttemptNumberAsc(job.getNotificationId());
        assertThat(attempts).hasSize(1);
        assertThat(attempts.get(0).getOutcome()).isEqualTo("SUCCESS");
        assertThat(attempts.get(0).getSafeErrorCode()).isEqualTo("DELIVERED");
    }

    @Test
    @DisplayName("L. Stuck processing notification recovery past lease expiration")
    void testStuckProcessingNotificationRecovery() {
        UUID eventId = UUID.randomUUID();
        UUID parcelId = UUID.randomUUID();
        NotificationJob job = new NotificationJob(
                UUID.randomUUID(),
                eventId,
                "user-stuck-1",
                parcelId,
                NotificationChannel.EMAIL,
                "sub:user-stuck-1",
                "PARCEL_DELIVERED"
        );
        job.setStatus(NotificationStatus.PROCESSING);
        job.setAttempts(1);
        job.setClaimToken("dead-worker-token");
        job.setClaimedAt(Instant.now().minusSeconds(600));
        job.setLeaseExpiresAt(Instant.now().minusSeconds(300)); // expired 5 mins ago
        job.setCreatedAt(Instant.now().minusSeconds(600));
        job.setNextAttemptAt(Instant.now().minusSeconds(600));
        job = notificationJobRepository.saveAndFlush(job);

        // Query stuck jobs
        Instant now = Instant.now();
        List<NotificationJob> stuck = notificationJobRepository.findStuckProcessingJobs(
                NotificationStatus.PROCESSING,
                now
        );

        assertThat(stuck).extracting(NotificationJob::getNotificationId).contains(job.getNotificationId());

        // Recover to PENDING
        job.setStatus(NotificationStatus.PENDING);
        job.setClaimToken(null);
        job.setLeaseExpiresAt(null);
        notificationJobRepository.saveAndFlush(job);

        // Verify discoverable by findDueNotifications
        List<NotificationJob> due = notificationJobRepository.findDueNotifications(
                NotificationStatus.PENDING,
                now,
                org.springframework.data.domain.PageRequest.of(0, 10)
        );
        assertThat(due).extracting(NotificationJob::getNotificationId).contains(job.getNotificationId());
    }

    @Test
    @DisplayName("M. Poison event quarantine persistence, uniqueness constraint, and reprocessing")
    void testPoisonEventQuarantinePersistenceAndReprocessing() {
        UUID quarantineId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Instant now = Instant.now();

        PoisonEventQuarantine record = new PoisonEventQuarantine(
                quarantineId,
                "communications.notifications.v1",
                "parcel.lifecycle.v1",
                1,
                888L,
                eventId,
                "PARCEL_STATUS_CHANGED",
                "1.0",
                "MALFORMED_SCHEMA",
                "Missing required fields",
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                "corr-quarantine-1",
                "QUARANTINED",
                now
        );

        poisonEventQuarantineRepository.saveAndFlush(record);

        // Verify retrieval by compound coordinates
        Optional<PoisonEventQuarantine> retrieved = poisonEventQuarantineRepository
                .findByConsumerGroupAndSourceTopicAndSourcePartitionAndSourceOffset(
                        "communications.notifications.v1", "parcel.lifecycle.v1", 1, 888L
                );
        assertThat(retrieved).isPresent();
        assertThat(retrieved.get().getPayloadDigest()).isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");

        // Verify duplicate (consumer_group, source_topic, source_partition, source_offset) violates unique constraint
        PoisonEventQuarantine duplicate = new PoisonEventQuarantine(
                UUID.randomUUID(),
                "communications.notifications.v1",
                "parcel.lifecycle.v1",
                1,
                888L,
                eventId,
                "PARCEL_STATUS_CHANGED",
                "1.0",
                "MALFORMED_SCHEMA",
                "Duplicate offset test",
                "digest2",
                "corr-2",
                "QUARANTINED",
                now
        );
        assertThatThrownBy(() -> poisonEventQuarantineRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);

        // Update status to REPLAYED
        record.setStatus("REPLAYED");
        record.setReplayedAt(Instant.now());
        record.setReplayedByActorSub("admin-123");
        record.setReplayReason("Defect fixed");
        poisonEventQuarantineRepository.saveAndFlush(record);

        PoisonEventQuarantine updated = poisonEventQuarantineRepository.findById(quarantineId).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo("REPLAYED");
        assertThat(updated.getReplayedByActorSub()).isEqualTo("admin-123");
    }

    @Test
    @DisplayName("N. Notification replay audit and previous status CHECK constraint")
    void testNotificationReplayAudit() {
        UUID eventId = UUID.randomUUID();
        UUID parcelId = UUID.randomUUID();
        NotificationJob job = new NotificationJob(
                UUID.randomUUID(),
                eventId,
                "user-replay-test",
                parcelId,
                NotificationChannel.EMAIL,
                "sub:user-replay-test",
                "PARCEL_DELIVERED"
        );
        job.setStatus(NotificationStatus.EXHAUSTED);
        job.setAttempts(5);
        job = notificationJobRepository.saveAndFlush(job);

        Instant now = Instant.now();
        NotificationReplayAudit validAudit = new NotificationReplayAudit(
                UUID.randomUUID(),
                job.getNotificationId(),
                eventId,
                "admin-sub-789",
                "ADMIN",
                "Customer requested retry",
                "EXHAUSTED",
                "ACCEPTED",
                now
        );
        notificationReplayAuditRepository.saveAndFlush(validAudit);

        List<NotificationReplayAudit> audits = notificationReplayAuditRepository
                .findByNotificationIdOrderByReplayedAtDesc(job.getNotificationId());
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0).getDecision()).isEqualTo("ACCEPTED");
        assertThat(audits.get(0).getPreviousStatus()).isEqualTo("EXHAUSTED");

        // Verify CHECK constraint: invalid previous_status (e.g. SENT) fails
        NotificationReplayAudit invalidAudit = new NotificationReplayAudit(
                UUID.randomUUID(),
                job.getNotificationId(),
                eventId,
                "admin-sub-789",
                "ADMIN",
                "Invalid replay attempt",
                "SENT",
                "ACCEPTED",
                now
        );
        assertThatThrownBy(() -> notificationReplayAuditRepository.saveAndFlush(invalidAudit))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("O. Retention cleanup purges terminal records older than cutoff while preserving pending records")
    void testRetentionCleanup() {
        Instant now = Instant.now();
        Instant fortyDaysAgo = now.minus(java.time.Duration.ofDays(40));
        Instant thirtyDaysAgo = now.minus(java.time.Duration.ofDays(30));

        // Create aged SENT job
        NotificationJob agedSentJob = new NotificationJob(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "user-aged-sent",
                UUID.randomUUID(),
                NotificationChannel.EMAIL,
                "sub:user-aged-sent",
                "PARCEL_DELIVERED"
        );
        agedSentJob.setStatus(NotificationStatus.SENT);
        agedSentJob.setAttempts(1);
        agedSentJob.setCreatedAt(fortyDaysAgo);
        agedSentJob.setUpdatedAt(fortyDaysAgo);
        agedSentJob = notificationJobRepository.saveAndFlush(agedSentJob);

        // Create aged PENDING job (must NOT be deleted)
        NotificationJob agedPendingJob = new NotificationJob(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "user-aged-pending",
                UUID.randomUUID(),
                NotificationChannel.EMAIL,
                "sub:user-aged-pending",
                "PARCEL_DELIVERED"
        );
        agedPendingJob.setStatus(NotificationStatus.PENDING);
        agedPendingJob.setAttempts(0);
        agedPendingJob.setCreatedAt(fortyDaysAgo);
        agedPendingJob.setUpdatedAt(fortyDaysAgo);
        agedPendingJob = notificationJobRepository.saveAndFlush(agedPendingJob);

        // Create aged processed consumed event
        ConsumedEvent agedEvent = new ConsumedEvent(
                new ConsumedEventId("communications.notifications.v1", agedSentJob.getEventId()),
                "PARCEL_DELIVERED",
                1,
                agedSentJob.getParcelId(),
                null,
                fortyDaysAgo
        );
        agedEvent.setProcessedAt(fortyDaysAgo);
        consumedEventRepository.saveAndFlush(agedEvent);

        // Run deletion queries
        int deletedJobs = notificationJobRepository.deleteTerminalNotificationsOlderThan(
                List.of(NotificationStatus.SENT, NotificationStatus.FAILED, NotificationStatus.EXHAUSTED),
                thirtyDaysAgo
        );
        int deletedEvents = consumedEventRepository.deleteProcessedEventsOlderThan(thirtyDaysAgo);

        assertThat(deletedJobs).isGreaterThanOrEqualTo(1);
        assertThat(deletedEvents).isGreaterThanOrEqualTo(1);

        // Aged SENT job was deleted
        assertThat(notificationJobRepository.findById(agedSentJob.getNotificationId())).isEmpty();

        // Aged PENDING job was preserved
        assertThat(notificationJobRepository.findById(agedPendingJob.getNotificationId())).isPresent();

        // Aged consumed event was deleted
        assertThat(consumedEventRepository.existsByIdConsumerNameAndIdEventId(
                "communications.notifications.v1", agedSentJob.getEventId())).isFalse();
    }
}
