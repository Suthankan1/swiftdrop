package com.swiftdrop.communicationsservice.consumer;

import com.swiftdrop.communicationsservice.consumer.event.ParcelStatusChangedEvent;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Service responsible for materializing durable webhook delivery jobs for eligible merchant subscriptions
 * upon consuming parcel lifecycle domain events.
 *
 * Enforces:
 * 1. Durable inbox deduplication per consumer group (communications.webhooks.v1).
 * 2. Merchant tenant filtering (direct retail parcels without merchantId create inbox receipt but zero jobs).
 * 3. Subscription status verification (active = true).
 * 4. Event type filter matching against subscription configuration.
 * 5. Monotonic state version checks (stale or out-of-order events with version <= existing delivery are skipped).
 * 6. Single atomic PostgreSQL transaction committing inbox receipt and delivery jobs together.
 * 7. Zero raw PII in durable job metadata.
 */
@Service
public class WebhookJobMaterializationService {

    private static final Logger log = LoggerFactory.getLogger(WebhookJobMaterializationService.class);

    private final DurableJobPersistenceService durableJobPersistenceService;
    private final WebhookSubscriptionRepository webhookSubscriptionRepository;
    private final WebhookDeliveryRepository webhookDeliveryRepository;
    private final WebhookEventFilterValidator eventFilterValidator;
    private final String consumerGroup;

    public WebhookJobMaterializationService(
            DurableJobPersistenceService durableJobPersistenceService,
            WebhookSubscriptionRepository webhookSubscriptionRepository,
            WebhookDeliveryRepository webhookDeliveryRepository,
            WebhookEventFilterValidator eventFilterValidator,
            @Value("${swiftdrop.kafka.consumer.webhooks.group-id:communications.webhooks.v1}") String consumerGroup) {
        this.durableJobPersistenceService = Objects.requireNonNull(durableJobPersistenceService, "durableJobPersistenceService cannot be null");
        this.webhookSubscriptionRepository = Objects.requireNonNull(webhookSubscriptionRepository, "webhookSubscriptionRepository cannot be null");
        this.webhookDeliveryRepository = Objects.requireNonNull(webhookDeliveryRepository, "webhookDeliveryRepository cannot be null");
        this.eventFilterValidator = Objects.requireNonNull(eventFilterValidator, "eventFilterValidator cannot be null");
        this.consumerGroup = (consumerGroup != null && !consumerGroup.isBlank())
                ? consumerGroup.trim()
                : "communications.webhooks.v1";
    }

    /**
     * Materializes durable webhook delivery jobs for an incoming lifecycle event.
     *
     * @param event validated parcel lifecycle event envelope
     * @return materialization outcome summary
     */
    public WebhookMaterializationResult processLifecycleEvent(ParcelStatusChangedEvent event) {
        return processLifecycleEvent(event, null);
    }

    /**
     * Materializes durable webhook delivery jobs for an incoming lifecycle event with raw JSON payload.
     *
     * @param event validated parcel lifecycle event envelope
     * @param rawPayload raw JSON string of the Kafka record value (for cryptographic payload digest)
     * @return materialization outcome summary
     */
    public WebhookMaterializationResult processLifecycleEvent(ParcelStatusChangedEvent event, String rawPayload) {
        Objects.requireNonNull(event, "event cannot be null");
        Objects.requireNonNull(event.eventId(), "event.eventId cannot be null");
        Objects.requireNonNull(event.payload(), "event.payload cannot be null");

        UUID eventId = event.eventId();
        UUID parcelId = event.payload().parcelId();
        UUID merchantId = event.payload().merchantId();
        int eventVersion = event.aggregateVersion() != null ? event.aggregateVersion().intValue() : 1;

        // 1. Inbox deduplication pre-check
        if (durableJobPersistenceService.isEventConsumed(consumerGroup, eventId)) {
            log.info("Event already recorded in webhook inbox [consumerGroup={}, eventId={}]. Skipping duplicate.",
                    consumerGroup, eventId);
            return WebhookMaterializationResult.duplicate(eventId);
        }

        // 2. Direct retail parcel check (merchantId is null for retail customers)
        if (merchantId == null) {
            log.info("Lifecycle event is for a direct retail parcel with no merchant tenant [eventId={}, parcelId={}]. " +
                    "Recording inbox receipt without webhook jobs.", eventId, parcelId);
            return recordInboxOnly(eventId, event.eventType(), eventVersion, parcelId, null, event.correlationId(),
                    WebhookMaterializationResult.retail(eventId));
        }

        // 3. Resolve active subscriptions for merchant tenant
        List<WebhookSubscription> activeSubscriptions =
                webhookSubscriptionRepository.findByMerchantIdAndActiveTrue(merchantId);

        if (activeSubscriptions.isEmpty()) {
            log.info("No active webhook subscriptions found for merchant tenant [merchantId={}, eventId={}]. " +
                    "Recording inbox receipt without webhook jobs.", merchantId, eventId);
            return recordInboxOnly(eventId, event.eventType(), eventVersion, parcelId, merchantId, event.correlationId(),
                    WebhookMaterializationResult.noActiveSubscriptions(eventId, merchantId));
        }

        // 4. Calculate payload digest
        String payloadDigest = computePayloadDigest(event, rawPayload);

        // 5. Evaluate subscriptions against event filter and monotonic state version
        List<WebhookDelivery> deliveriesToPersist = new ArrayList<>();
        boolean hadSubscribedEndpoint = false;
        boolean allStale = true;

        for (WebhookSubscription sub : activeSubscriptions) {
            List<String> allowedEvents = eventFilterValidator.deserialize(sub.getAllowedEventTypes());
            if (!allowedEvents.contains(event.eventType())) {
                log.debug("Subscription does not subscribe to eventType [subscriptionId={}, eventType={}]. Skipping.",
                        sub.getSubscriptionId(), event.eventType());
                continue;
            }

            hadSubscribedEndpoint = true;

            // Stale-version & reordering guard:
            // Check if this subscription already has a delivery job for this parcel with state_version >= incoming eventVersion
            Optional<WebhookDelivery> latestDelivery = webhookDeliveryRepository
                    .findTopBySubscriptionIdAndParcelIdOrderByStateVersionDesc(sub.getSubscriptionId(), parcelId);

            if (latestDelivery.isPresent() && latestDelivery.get().getStateVersion() >= eventVersion) {
                log.warn("Stale or duplicate lifecycle event detected for subscription [subscriptionId={}, parcelId={}, " +
                                "eventVersion={}, existingVersion={}]. Skipping job creation to prevent stale update.",
                        sub.getSubscriptionId(), parcelId, eventVersion, latestDelivery.get().getStateVersion());
                continue;
            }

            allStale = false;

            WebhookDelivery delivery = new WebhookDelivery(
                    UUID.randomUUID(),
                    sub.getSubscriptionId(),
                    eventId,
                    parcelId,
                    merchantId,
                    eventVersion,
                    payloadDigest
            );
            delivery.setStatus(WebhookDeliveryStatus.PENDING);
            delivery.setAttemptCount(0);
            delivery.setNextAttemptAt(Instant.now());
            delivery.setCorrelationId(event.correlationId());
            deliveriesToPersist.add(delivery);
        }

        // If subscribed endpoints exist but all are stale, record inbox to avoid redelivery loop
        if (deliveriesToPersist.isEmpty()) {
            if (hadSubscribedEndpoint && allStale) {
                log.info("All subscribed webhook endpoints for parcel already have equal or newer state versions " +
                        "[parcelId={}, eventId={}, eventVersion={}]. Recording inbox receipt.", parcelId, eventId, eventVersion);
                return recordInboxOnly(eventId, event.eventType(), eventVersion, parcelId, merchantId, event.correlationId(),
                        WebhookMaterializationResult.stale(eventId, parcelId));
            } else {
                log.info("No subscriptions match eventType [merchantId={}, eventType={}, eventId={}]. " +
                        "Recording inbox receipt without jobs.", merchantId, event.eventType(), eventId);
                return recordInboxOnly(eventId, event.eventType(), eventVersion, parcelId, merchantId, event.correlationId(),
                        new WebhookMaterializationResult(eventId, true, false, 0, List.of(), "NO_MATCHING_EVENT_TYPES"));
            }
        }

        // 6. Persist inbox receipt + all delivery jobs atomically in PostgreSQL
        ConsumedEvent consumedEvent = new ConsumedEvent(
                new ConsumedEventId(consumerGroup, eventId),
                event.eventType(),
                eventVersion,
                parcelId,
                merchantId,
                Instant.now()
        );
        consumedEvent.setScheduledAt(Instant.now());
        consumedEvent.setCorrelationId(event.correlationId());

        try {
            InboxAndWebhookDeliveriesResult result =
                    durableJobPersistenceService.persistInboxAndWebhookDeliveries(consumedEvent, deliveriesToPersist);

            log.info("Persisted inbox receipt and materialized {} webhook delivery jobs " +
                            "[consumerGroup={}, eventId={}, parcelId={}, merchantId={}]",
                    deliveriesToPersist.size(), consumerGroup, eventId, parcelId, merchantId);

            return WebhookMaterializationResult.persisted(eventId, result.webhookDeliveries());

        } catch (DuplicateEventException ex) {
            log.info("Concurrent duplicate detected during webhook persist [consumerGroup={}, eventId={}]. " +
                    "Skipping duplicate.", consumerGroup, eventId);
            return WebhookMaterializationResult.duplicate(eventId);
        }
    }

    private WebhookMaterializationResult recordInboxOnly(
            UUID eventId, String eventType, int version, UUID parcelId, UUID merchantId, String correlationId,
            WebhookMaterializationResult outcomeOnSuccess) {
        ConsumedEvent consumedEvent = new ConsumedEvent(
                new ConsumedEventId(consumerGroup, eventId),
                eventType,
                version,
                parcelId,
                merchantId,
                Instant.now()
        );
        consumedEvent.setScheduledAt(Instant.now());
        consumedEvent.setCorrelationId(correlationId);

        try {
            durableJobPersistenceService.persistInboxAndWebhookDeliveries(consumedEvent, List.of());
            return outcomeOnSuccess;
        } catch (DuplicateEventException ex) {
            log.info("Concurrent duplicate detected during inbox record [consumerGroup={}, eventId={}]. " +
                    "Skipping duplicate.", consumerGroup, eventId);
            return WebhookMaterializationResult.duplicate(eventId);
        }
    }

    private String computePayloadDigest(ParcelStatusChangedEvent event, String rawPayload) {
        String dataToHash = (rawPayload != null && !rawPayload.isBlank())
                ? rawPayload.trim()
                : (event.eventId() + ":" + event.aggregateId() + ":" + event.aggregateVersion() + ":" +
                (event.payload() != null ? event.payload().newStatus() : ""));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(dataToHash.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest algorithm not available", e);
        }
    }

    public String getConsumerGroup() {
        return consumerGroup;
    }
}
