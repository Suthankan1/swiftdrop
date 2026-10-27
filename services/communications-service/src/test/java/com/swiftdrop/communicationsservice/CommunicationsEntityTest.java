package com.swiftdrop.communicationsservice;

import com.swiftdrop.communicationsservice.inbox.ConsumedEvent;
import com.swiftdrop.communicationsservice.inbox.ConsumedEventId;
import com.swiftdrop.communicationsservice.notification.NotificationChannel;
import com.swiftdrop.communicationsservice.notification.NotificationJob;
import com.swiftdrop.communicationsservice.notification.NotificationStatus;
import com.swiftdrop.communicationsservice.webhook.WebhookAttempt;
import com.swiftdrop.communicationsservice.webhook.WebhookDelivery;
import com.swiftdrop.communicationsservice.webhook.WebhookDeliveryStatus;
import com.swiftdrop.communicationsservice.webhook.WebhookSubscription;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CommunicationsEntityTest {

    @Test
    @DisplayName("Verify ConsumedEventId equals, hashCode, and toString")
    void testConsumedEventId() {
        UUID eventId = UUID.randomUUID();
        ConsumedEventId id1 = new ConsumedEventId("consumer-a", eventId);
        ConsumedEventId id2 = new ConsumedEventId("consumer-a", eventId);
        ConsumedEventId id3 = new ConsumedEventId("consumer-b", eventId);

        assertThat(id1).isEqualTo(id2);
        assertThat(id1.hashCode()).isEqualTo(id2.hashCode());
        assertThat(id1).isNotEqualTo(id3);
        assertThat(id1.toString()).contains("consumer-a", eventId.toString());

        ConsumedEventId empty = new ConsumedEventId();
        empty.setConsumerName("c");
        empty.setEventId(eventId);
        assertThat(empty.getConsumerName()).isEqualTo("c");
        assertThat(empty.getEventId()).isEqualTo(eventId);
    }

    @Test
    @DisplayName("Verify ConsumedEvent entity fields and lifecycle")
    void testConsumedEvent() {
        UUID eventId = UUID.randomUUID();
        UUID parcelId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        Instant now = Instant.now();
        ConsumedEventId id = new ConsumedEventId("consumer", eventId);
        ConsumedEvent event = new ConsumedEvent(id, "parcel.status.changed.v1", 2, parcelId, merchantId, now);

        assertThat(event.getId()).isEqualTo(id);
        assertThat(event.getConsumerName()).isEqualTo("consumer");
        assertThat(event.getEventId()).isEqualTo(eventId);
        assertThat(event.getEventType()).isEqualTo("parcel.status.changed.v1");
        assertThat(event.getVersion()).isEqualTo(2);
        assertThat(event.getParcelId()).isEqualTo(parcelId);
        assertThat(event.getMerchantId()).isEqualTo(merchantId);
        assertThat(event.getReceivedAt()).isEqualTo(now);

        Instant processed = Instant.now();
        Instant scheduled = Instant.now().plusSeconds(60);
        event.setProcessedAt(processed);
        event.setScheduledAt(scheduled);
        assertThat(event.getProcessedAt()).isEqualTo(processed);
        assertThat(event.getScheduledAt()).isEqualTo(scheduled);
    }

    @Test
    @DisplayName("Verify NotificationJob entity fields and prePersist/preUpdate lifecycle")
    void testNotificationJob() {
        UUID eventId = UUID.randomUUID();
        UUID parcelId = UUID.randomUUID();
        NotificationJob job = new NotificationJob(null, eventId, "sub-1", parcelId,
                NotificationChannel.EMAIL, "user@test.test", "PARCEL_CREATED");

        job.prePersist();
        assertThat(job.getNotificationId()).isNotNull();
        assertThat(job.getCreatedAt()).isNotNull();
        assertThat(job.getUpdatedAt()).isNotNull();
        assertThat(job.getStatus()).isEqualTo(NotificationStatus.PENDING);
        assertThat(job.getAttempts()).isZero();

        job.setStatus(NotificationStatus.SENT);
        job.setAttempts(1);
        job.preUpdate();
        assertThat(job.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(job.getAttempts()).isEqualTo(1);
    }

    @Test
    @DisplayName("Verify WebhookSubscription entity fields and prePersist/preUpdate lifecycle")
    void testWebhookSubscription() {
        UUID merchantId = UUID.randomUUID();
        WebhookSubscription sub = new WebhookSubscription(null, merchantId,
                "https://example.test/webhook", "parcel.status.changed.v1", "vault-ref-123");

        sub.prePersist();
        assertThat(sub.getSubscriptionId()).isNotNull();
        assertThat(sub.getActive()).isTrue();
        assertThat(sub.getCreatedAt()).isNotNull();
        assertThat(sub.getUpdatedAt()).isNotNull();
        assertThat(sub.getHttpsUrl()).isEqualTo("https://example.test/webhook");

        sub.setActive(false);
        sub.preUpdate();
        assertThat(sub.getActive()).isFalse();
    }

    @Test
    @DisplayName("Verify WebhookDelivery entity fields and prePersist/preUpdate lifecycle")
    void testWebhookDelivery() {
        UUID subId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID parcelId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        WebhookDelivery delivery = new WebhookDelivery(null, subId, eventId, parcelId, merchantId, 1, "digest-abc");

        delivery.prePersist();
        assertThat(delivery.getDeliveryId()).isNotNull();
        assertThat(delivery.getStatus()).isEqualTo(WebhookDeliveryStatus.PENDING);
        assertThat(delivery.getAttemptCount()).isZero();
        assertThat(delivery.getCreatedAt()).isNotNull();

        delivery.setStatus(WebhookDeliveryStatus.SUCCESS);
        delivery.setAttemptCount(1);
        delivery.preUpdate();
        assertThat(delivery.getStatus()).isEqualTo(WebhookDeliveryStatus.SUCCESS);
    }

    @Test
    @DisplayName("Verify WebhookAttempt entity fields and prePersist lifecycle")
    void testWebhookAttempt() {
        UUID deliveryId = UUID.randomUUID();
        WebhookAttempt attempt = new WebhookAttempt(null, deliveryId, null, 200, 145, null);

        attempt.prePersist();
        assertThat(attempt.getAttemptId()).isNotNull();
        assertThat(attempt.getAttemptedAt()).isNotNull();
        assertThat(attempt.getHttpStatusCode()).isEqualTo(200);
        assertThat(attempt.getResponseTimeMs()).isEqualTo(145);
        assertThat(attempt.getErrorSummary()).isNull();
    }

    @Test
    @DisplayName("Verify WebhookReplayAudit entity fields and getters/setters")
    void testWebhookReplayAudit() {
        UUID replayId = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();
        UUID subId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        Instant now = Instant.now();

        com.swiftdrop.communicationsservice.webhook.WebhookReplayAudit audit =
                new com.swiftdrop.communicationsservice.webhook.WebhookReplayAudit(
                        replayId, deliveryId, subId, merchantId, "actor-123", "ADMIN", "Manual replay", "EXHAUSTED", now);

        assertThat(audit.getReplayId()).isEqualTo(replayId);
        assertThat(audit.getDeliveryId()).isEqualTo(deliveryId);
        assertThat(audit.getSubscriptionId()).isEqualTo(subId);
        assertThat(audit.getMerchantId()).isEqualTo(merchantId);
        assertThat(audit.getActorSub()).isEqualTo("actor-123");
        assertThat(audit.getActorRole()).isEqualTo("ADMIN");
        assertThat(audit.getReason()).isEqualTo("Manual replay");
        assertThat(audit.getPreviousStatus()).isEqualTo("EXHAUSTED");
        assertThat(audit.getReplayedAt()).isEqualTo(now);

        com.swiftdrop.communicationsservice.webhook.WebhookReplayAudit empty =
                new com.swiftdrop.communicationsservice.webhook.WebhookReplayAudit();
        empty.setReplayId(replayId);
        empty.setActorRole("MERCHANT");
        assertThat(empty.getReplayId()).isEqualTo(replayId);
        assertThat(empty.getActorRole()).isEqualTo("MERCHANT");
    }
}
