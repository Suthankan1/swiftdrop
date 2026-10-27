package com.swiftdrop.communicationsservice.consumer;

import com.swiftdrop.communicationsservice.consumer.event.ParcelStatusChangedEvent;
import com.swiftdrop.communicationsservice.consumer.event.ParcelStatusChangedPayload;
import com.swiftdrop.communicationsservice.inbox.ConsumedEvent;
import com.swiftdrop.communicationsservice.inbox.ConsumedEventId;
import com.swiftdrop.communicationsservice.transaction.DurableJobPersistenceService;
import com.swiftdrop.communicationsservice.transaction.DuplicateEventException;
import com.swiftdrop.communicationsservice.transaction.InboxAndWebhookDeliveriesResult;
import com.swiftdrop.communicationsservice.webhook.WebhookDelivery;
import com.swiftdrop.communicationsservice.webhook.WebhookDeliveryRepository;
import com.swiftdrop.communicationsservice.webhook.WebhookDeliveryStatus;
import com.swiftdrop.communicationsservice.webhook.WebhookSubscription;
import com.swiftdrop.communicationsservice.webhook.WebhookSubscriptionRepository;
import com.swiftdrop.communicationsservice.webhook.validation.WebhookEventFilterValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WebhookJobMaterializationServiceTest {

    @Mock
    private DurableJobPersistenceService durableJobPersistenceService;

    @Mock
    private WebhookSubscriptionRepository webhookSubscriptionRepository;

    @Mock
    private WebhookDeliveryRepository webhookDeliveryRepository;

    private WebhookEventFilterValidator eventFilterValidator;
    private WebhookJobMaterializationService service;

    private final String consumerGroup = "communications.webhooks.v1";
    private UUID eventId;
    private UUID parcelId;
    private UUID merchantId;
    private ParcelStatusChangedEvent sampleEvent;

    @BeforeEach
    void setUp() {
        eventFilterValidator = new WebhookEventFilterValidator();
        service = new WebhookJobMaterializationService(
                durableJobPersistenceService,
                webhookSubscriptionRepository,
                webhookDeliveryRepository,
                eventFilterValidator,
                consumerGroup
        );

        eventId = UUID.randomUUID();
        parcelId = UUID.randomUUID();
        merchantId = UUID.randomUUID();

        ParcelStatusChangedPayload payload = new ParcelStatusChangedPayload(
                parcelId,
                "SWD-20261026-A1B2",
                "OUT_FOR_DELIVERY",
                "DELIVERED",
                null,
                merchantId,
                "courier-1",
                "Western Province Hub"
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
    @DisplayName("Single active subscription materializes exactly one durable webhook delivery job")
    void testSuccessfulMaterialization_SingleSubscription() {
        UUID subscriptionId = UUID.randomUUID();
        WebhookSubscription subscription = new WebhookSubscription(
                subscriptionId,
                merchantId,
                "https://merchant.example.com/callbacks",
                "parcel.status.changed.v1",
                "secret-ref-1"
        );

        when(durableJobPersistenceService.isEventConsumed(consumerGroup, eventId)).thenReturn(false);
        when(webhookSubscriptionRepository.findByMerchantIdAndActiveTrue(merchantId))
                .thenReturn(List.of(subscription));
        when(webhookDeliveryRepository.findTopBySubscriptionIdAndParcelIdOrderByStateVersionDesc(subscriptionId, parcelId))
                .thenReturn(Optional.empty());

        ConsumedEvent persistedEvent = new ConsumedEvent(
                new ConsumedEventId(consumerGroup, eventId),
                "parcel.status.changed.v1",
                2,
                parcelId,
                merchantId,
                Instant.now()
        );
        WebhookDelivery persistedDelivery = new WebhookDelivery(
                UUID.randomUUID(),
                subscriptionId,
                eventId,
                parcelId,
                merchantId,
                2,
                "digest"
        );

        when(durableJobPersistenceService.persistInboxAndWebhookDeliveries(any(ConsumedEvent.class), anyList()))
                .thenReturn(new InboxAndWebhookDeliveriesResult(persistedEvent, List.of(persistedDelivery), true));

        WebhookMaterializationResult result = service.processLifecycleEvent(sampleEvent, "{\"raw\":\"payload\"}");

        assertThat(result).isNotNull();
        assertThat(result.isDuplicate()).isFalse();
        assertThat(result.newlyPersisted()).isTrue();
        assertThat(result.jobsCreatedCount()).isEqualTo(1);
        assertThat(result.jobs()).hasSize(1);

        ArgumentCaptor<ConsumedEvent> eventCaptor = ArgumentCaptor.forClass(ConsumedEvent.class);
        ArgumentCaptor<List<WebhookDelivery>> deliveriesCaptor = ArgumentCaptor.forClass(List.class);
        verify(durableJobPersistenceService).persistInboxAndWebhookDeliveries(eventCaptor.capture(), deliveriesCaptor.capture());

        ConsumedEvent capturedEvent = eventCaptor.getValue();
        assertThat(capturedEvent.getConsumerName()).isEqualTo(consumerGroup);
        assertThat(capturedEvent.getEventId()).isEqualTo(eventId);
        assertThat(capturedEvent.getParcelId()).isEqualTo(parcelId);
        assertThat(capturedEvent.getMerchantId()).isEqualTo(merchantId);
        assertThat(capturedEvent.getVersion()).isEqualTo(2);

        List<WebhookDelivery> capturedDeliveries = deliveriesCaptor.getValue();
        assertThat(capturedDeliveries).hasSize(1);
        WebhookDelivery delivery = capturedDeliveries.get(0);
        assertThat(delivery.getSubscriptionId()).isEqualTo(subscriptionId);
        assertThat(delivery.getEventId()).isEqualTo(eventId);
        assertThat(delivery.getParcelId()).isEqualTo(parcelId);
        assertThat(delivery.getMerchantId()).isEqualTo(merchantId);
        assertThat(delivery.getStateVersion()).isEqualTo(2);
        assertThat(delivery.getStatus()).isEqualTo(WebhookDeliveryStatus.PENDING);
        assertThat(delivery.getAttemptCount()).isZero();
        assertThat(delivery.getNextAttemptAt()).isNotNull();
        assertThat(delivery.getSignedPayloadDigest()).isNotNull().hasSize(64);
    }

    @Test
    @DisplayName("Multiple active subscriptions materialize one job per intended subscription")
    void testSuccessfulMaterialization_MultipleSubscriptions() {
        UUID subId1 = UUID.randomUUID();
        UUID subId2 = UUID.randomUUID();
        WebhookSubscription sub1 = new WebhookSubscription(subId1, merchantId, "https://m1.com/hook", "parcel.status.changed.v1", "s1");
        WebhookSubscription sub2 = new WebhookSubscription(subId2, merchantId, "https://m2.com/hook", "parcel.status.changed.v1", "s2");

        when(durableJobPersistenceService.isEventConsumed(consumerGroup, eventId)).thenReturn(false);
        when(webhookSubscriptionRepository.findByMerchantIdAndActiveTrue(merchantId))
                .thenReturn(List.of(sub1, sub2));
        when(webhookDeliveryRepository.findTopBySubscriptionIdAndParcelIdOrderByStateVersionDesc(subId1, parcelId))
                .thenReturn(Optional.empty());
        when(webhookDeliveryRepository.findTopBySubscriptionIdAndParcelIdOrderByStateVersionDesc(subId2, parcelId))
                .thenReturn(Optional.empty());

        ConsumedEvent persistedEvent = new ConsumedEvent(new ConsumedEventId(consumerGroup, eventId), "parcel.status.changed.v1", 2, parcelId, merchantId, Instant.now());
        when(durableJobPersistenceService.persistInboxAndWebhookDeliveries(any(), anyList()))
                .thenAnswer(inv -> new InboxAndWebhookDeliveriesResult(persistedEvent, inv.getArgument(1), true));

        WebhookMaterializationResult result = service.processLifecycleEvent(sampleEvent);

        assertThat(result.jobsCreatedCount()).isEqualTo(2);

        ArgumentCaptor<List<WebhookDelivery>> captor = ArgumentCaptor.forClass(List.class);
        verify(durableJobPersistenceService).persistInboxAndWebhookDeliveries(any(), captor.capture());
        List<WebhookDelivery> jobs = captor.getValue();
        assertThat(jobs).hasSize(2);
        assertThat(jobs).extracting(WebhookDelivery::getSubscriptionId).containsExactlyInAnyOrder(subId1, subId2);
    }

    @Test
    @DisplayName("Direct retail parcel with null merchantId records inbox receipt without creating webhook jobs")
    void testDirectRetailParcel_CreatesInboxOnlyNoWebhookJobs() {
        ParcelStatusChangedPayload retailPayload = new ParcelStatusChangedPayload(
                parcelId, "SWD-20261026-A1B2", "OUT_FOR_DELIVERY", "DELIVERED", null, null, "courier-1", "Colombo"
        );
        ParcelStatusChangedEvent retailEvent = new ParcelStatusChangedEvent(
                eventId, "parcel.status.changed.v1", "1.0.0", parcelId, "PARCEL", 2L, Instant.now(), "corr-1", "delivery-operations", retailPayload
        );

        when(durableJobPersistenceService.isEventConsumed(consumerGroup, eventId)).thenReturn(false);

        WebhookMaterializationResult result = service.processLifecycleEvent(retailEvent);

        assertThat(result.isDuplicate()).isFalse();
        assertThat(result.jobsCreatedCount()).isZero();
        assertThat(result.summary()).isEqualTo("RETAIL_PARCEL_NO_MERCHANT");

        verify(webhookSubscriptionRepository, never()).findByMerchantIdAndActiveTrue(any());
        ArgumentCaptor<ConsumedEvent> eventCaptor = ArgumentCaptor.forClass(ConsumedEvent.class);
        ArgumentCaptor<List<WebhookDelivery>> deliveriesCaptor = ArgumentCaptor.forClass(List.class);
        verify(durableJobPersistenceService).persistInboxAndWebhookDeliveries(eventCaptor.capture(), deliveriesCaptor.capture());

        assertThat(eventCaptor.getValue().getMerchantId()).isNull();
        assertThat(deliveriesCaptor.getValue()).isEmpty();
    }

    @Test
    @DisplayName("Merchant with zero active subscriptions records inbox receipt without creating webhook jobs")
    void testNoActiveSubscriptions_CreatesInboxOnlyNoWebhookJobs() {
        when(durableJobPersistenceService.isEventConsumed(consumerGroup, eventId)).thenReturn(false);
        when(webhookSubscriptionRepository.findByMerchantIdAndActiveTrue(merchantId)).thenReturn(List.of());

        WebhookMaterializationResult result = service.processLifecycleEvent(sampleEvent);

        assertThat(result.jobsCreatedCount()).isZero();
        assertThat(result.summary()).contains("NO_ACTIVE_SUBSCRIPTIONS");

        ArgumentCaptor<List<WebhookDelivery>> deliveriesCaptor = ArgumentCaptor.forClass(List.class);
        verify(durableJobPersistenceService).persistInboxAndWebhookDeliveries(any(), deliveriesCaptor.capture());
        assertThat(deliveriesCaptor.getValue()).isEmpty();
    }

    @Test
    @DisplayName("Subscription whose event filter does not match incoming eventType is safely skipped")
    void testUnsubscribedEventType_DoesNotCreateDeliveryJob() {
        UUID subId = UUID.randomUUID();
        // Suppose subscription only allows some other hypothetical event type (not parcel.status.changed.v1)
        WebhookSubscription sub = new WebhookSubscription(subId, merchantId, "https://m.com/hook", "order.created.v1", "s");

        when(durableJobPersistenceService.isEventConsumed(consumerGroup, eventId)).thenReturn(false);
        when(webhookSubscriptionRepository.findByMerchantIdAndActiveTrue(merchantId)).thenReturn(List.of(sub));

        WebhookMaterializationResult result = service.processLifecycleEvent(sampleEvent);

        assertThat(result.jobsCreatedCount()).isZero();
        ArgumentCaptor<List<WebhookDelivery>> deliveriesCaptor = ArgumentCaptor.forClass(List.class);
        verify(durableJobPersistenceService).persistInboxAndWebhookDeliveries(any(), deliveriesCaptor.capture());
        assertThat(deliveriesCaptor.getValue()).isEmpty();
    }

    @Test
    @DisplayName("Idempotency: Event already recorded in inbox returns duplicate without modifying jobs")
    void testIdempotency_DuplicateEventInInbox_ReturnsDuplicate() {
        when(durableJobPersistenceService.isEventConsumed(consumerGroup, eventId)).thenReturn(true);

        WebhookMaterializationResult result = service.processLifecycleEvent(sampleEvent);

        assertThat(result.isDuplicate()).isTrue();
        assertThat(result.newlyPersisted()).isFalse();
        assertThat(result.jobsCreatedCount()).isZero();

        verify(webhookSubscriptionRepository, never()).findByMerchantIdAndActiveTrue(any());
        verify(durableJobPersistenceService, never()).persistInboxAndWebhookDeliveries(any(), anyList());
    }

    @Test
    @DisplayName("Concurrent duplicate throwing DuplicateEventException is safely caught and returns duplicate")
    void testConcurrentDuplicate_HandledSafely() {
        UUID subscriptionId = UUID.randomUUID();
        WebhookSubscription sub = new WebhookSubscription(subscriptionId, merchantId, "https://m.com/hook", "parcel.status.changed.v1", "s");

        when(durableJobPersistenceService.isEventConsumed(consumerGroup, eventId)).thenReturn(false);
        when(webhookSubscriptionRepository.findByMerchantIdAndActiveTrue(merchantId)).thenReturn(List.of(sub));
        when(webhookDeliveryRepository.findTopBySubscriptionIdAndParcelIdOrderByStateVersionDesc(subscriptionId, parcelId))
                .thenReturn(Optional.empty());

        when(durableJobPersistenceService.persistInboxAndWebhookDeliveries(any(), anyList()))
                .thenThrow(new DuplicateEventException(consumerGroup, eventId));

        WebhookMaterializationResult result = service.processLifecycleEvent(sampleEvent);

        assertThat(result.isDuplicate()).isTrue();
        assertThat(result.newlyPersisted()).isFalse();
        assertThat(result.jobsCreatedCount()).isZero();
    }

    @Test
    @DisplayName("Stale event with aggregateVersion <= existing delivery's stateVersion skips job creation")
    void testStaleEvent_SkipsJobMaterialization() {
        UUID subscriptionId = UUID.randomUUID();
        WebhookSubscription sub = new WebhookSubscription(subscriptionId, merchantId, "https://m.com/hook", "parcel.status.changed.v1", "s");

        // Existing delivery in database has state_version = 3 (newer than incoming sampleEvent version 2)
        WebhookDelivery existingNewerDelivery = new WebhookDelivery(
                UUID.randomUUID(), subscriptionId, UUID.randomUUID(), parcelId, merchantId, 3, "digest"
        );

        when(durableJobPersistenceService.isEventConsumed(consumerGroup, eventId)).thenReturn(false);
        when(webhookSubscriptionRepository.findByMerchantIdAndActiveTrue(merchantId)).thenReturn(List.of(sub));
        when(webhookDeliveryRepository.findTopBySubscriptionIdAndParcelIdOrderByStateVersionDesc(subscriptionId, parcelId))
                .thenReturn(Optional.of(existingNewerDelivery));

        WebhookMaterializationResult result = service.processLifecycleEvent(sampleEvent);

        assertThat(result.jobsCreatedCount()).isZero();
        assertThat(result.summary()).contains("SUPERSEDED_STALE_EVENT");

        // Inbox is still recorded so Kafka offset advances without infinite redelivery loop
        ArgumentCaptor<List<WebhookDelivery>> deliveriesCaptor = ArgumentCaptor.forClass(List.class);
        verify(durableJobPersistenceService).persistInboxAndWebhookDeliveries(any(), deliveriesCaptor.capture());
        assertThat(deliveriesCaptor.getValue()).isEmpty();
    }

    @Test
    @DisplayName("Equal aggregate version for same parcel already present is treated as stale/duplicate and skipped")
    void testEqualVersion_SkipsJobMaterialization() {
        UUID subscriptionId = UUID.randomUUID();
        WebhookSubscription sub = new WebhookSubscription(subscriptionId, merchantId, "https://m.com/hook", "parcel.status.changed.v1", "s");

        // Existing delivery in database already has version = 2 (sampleEvent version is 2)
        WebhookDelivery existingDelivery = new WebhookDelivery(
                UUID.randomUUID(), subscriptionId, UUID.randomUUID(), parcelId, merchantId, 2, "digest"
        );

        when(durableJobPersistenceService.isEventConsumed(consumerGroup, eventId)).thenReturn(false);
        when(webhookSubscriptionRepository.findByMerchantIdAndActiveTrue(merchantId)).thenReturn(List.of(sub));
        when(webhookDeliveryRepository.findTopBySubscriptionIdAndParcelIdOrderByStateVersionDesc(subscriptionId, parcelId))
                .thenReturn(Optional.of(existingDelivery));

        WebhookMaterializationResult result = service.processLifecycleEvent(sampleEvent);

        assertThat(result.jobsCreatedCount()).isZero();
        assertThat(result.summary()).contains("SUPERSEDED_STALE_EVENT");
    }

    @Test
    @DisplayName("Zero PII: Materialized WebhookDelivery jobs contain strictly opaque scalar IDs and zero customer PII")
    void testZeroCustomerPII() {
        UUID subscriptionId = UUID.randomUUID();
        WebhookSubscription sub = new WebhookSubscription(subscriptionId, merchantId, "https://m.com/hook", "parcel.status.changed.v1", "s");

        when(durableJobPersistenceService.isEventConsumed(consumerGroup, eventId)).thenReturn(false);
        when(webhookSubscriptionRepository.findByMerchantIdAndActiveTrue(merchantId)).thenReturn(List.of(sub));
        when(webhookDeliveryRepository.findTopBySubscriptionIdAndParcelIdOrderByStateVersionDesc(subscriptionId, parcelId))
                .thenReturn(Optional.empty());

        ConsumedEvent persistedEvent = new ConsumedEvent(new ConsumedEventId(consumerGroup, eventId), "parcel.status.changed.v1", 2, parcelId, merchantId, Instant.now());
        when(durableJobPersistenceService.persistInboxAndWebhookDeliveries(any(), anyList()))
                .thenAnswer(inv -> new InboxAndWebhookDeliveriesResult(persistedEvent, inv.getArgument(1), true));

        service.processLifecycleEvent(sampleEvent, "{\"raw\":\"payload\"}");

        ArgumentCaptor<List<WebhookDelivery>> captor = ArgumentCaptor.forClass(List.class);
        verify(durableJobPersistenceService).persistInboxAndWebhookDeliveries(any(), captor.capture());
        WebhookDelivery delivery = captor.getValue().get(0);

        // Verify only opaque scalar IDs are present
        assertThat(delivery.getParcelId()).isEqualTo(parcelId);
        assertThat(delivery.getMerchantId()).isEqualTo(merchantId);
        assertThat(delivery.getEventId()).isEqualTo(eventId);
        assertThat(delivery.getSubscriptionId()).isEqualTo(subscriptionId);
        // Signed payload digest is 64 hex chars
        assertThat(delivery.getSignedPayloadDigest()).matches("^[a-f0-9]{64}$");
    }

    @Test
    @DisplayName("Null arguments are safely rejected with NullPointerException")
    void testNullValidation() {
        assertThatThrownBy(() -> service.processLifecycleEvent(null))
                .isInstanceOf(NullPointerException.class);

        ParcelStatusChangedEvent eventWithNullId = new ParcelStatusChangedEvent(
                null, "type", "1.0.0", parcelId, "PARCEL", 1L, Instant.now(), "c", "p", sampleEvent.payload()
        );
        assertThatThrownBy(() -> service.processLifecycleEvent(eventWithNullId))
                .isInstanceOf(NullPointerException.class);

        ParcelStatusChangedEvent eventWithNullPayload = new ParcelStatusChangedEvent(
                eventId, "type", "1.0.0", parcelId, "PARCEL", 1L, Instant.now(), "c", "p", null
        );
        assertThatThrownBy(() -> service.processLifecycleEvent(eventWithNullPayload))
                .isInstanceOf(NullPointerException.class);
    }
}
