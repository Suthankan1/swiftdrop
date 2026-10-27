package com.swiftdrop.communicationsservice.consumer;

import com.swiftdrop.communicationsservice.client.AccountsRecipientContactClient;
import com.swiftdrop.communicationsservice.client.DeliveryAuthorizationException;
import com.swiftdrop.communicationsservice.client.DeliveryEventAuthorization;
import com.swiftdrop.communicationsservice.client.DeliveryRecipientAuthorizationClient;
import com.swiftdrop.communicationsservice.client.PermittedRecipientContact;
import com.swiftdrop.communicationsservice.consumer.event.ParcelStatusChangedEvent;
import com.swiftdrop.communicationsservice.inbox.ConsumedEvent;
import com.swiftdrop.communicationsservice.inbox.ConsumedEventId;
import com.swiftdrop.communicationsservice.notification.NotificationChannel;
import com.swiftdrop.communicationsservice.notification.NotificationJob;
import com.swiftdrop.communicationsservice.notification.NotificationStatus;
import com.swiftdrop.communicationsservice.transaction.DurableJobPersistenceService;
import com.swiftdrop.communicationsservice.transaction.DuplicateEventException;
import com.swiftdrop.communicationsservice.transaction.InboxAndNotificationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Coordinates event authorization, permitted contact resolution, and atomic inbox/job persistence.
 */
@Service
public class NotificationSchedulingService {

    private static final Logger log = LoggerFactory.getLogger(NotificationSchedulingService.class);
    public static final String NOTIFICATION_PURPOSE = "PARCEL_STATUS_UPDATE";

    private final DurableJobPersistenceService durableJobPersistenceService;
    private final DeliveryRecipientAuthorizationClient deliveryAuthorizationClient;
    private final AccountsRecipientContactClient accountsContactClient;
    private final String consumerGroup;

    public NotificationSchedulingService(
            DurableJobPersistenceService durableJobPersistenceService,
            DeliveryRecipientAuthorizationClient deliveryAuthorizationClient,
            AccountsRecipientContactClient accountsContactClient,
            @Value("${spring.kafka.consumer.group-id:communications.notifications.v1}") String consumerGroup) {
        this.durableJobPersistenceService = Objects.requireNonNull(durableJobPersistenceService, "durableJobPersistenceService cannot be null");
        this.deliveryAuthorizationClient = Objects.requireNonNull(deliveryAuthorizationClient, "deliveryAuthorizationClient cannot be null");
        this.accountsContactClient = Objects.requireNonNull(accountsContactClient, "accountsContactClient cannot be null");
        this.consumerGroup = (consumerGroup != null && !consumerGroup.isBlank())
                ? consumerGroup.trim()
                : "communications.notifications.v1";
    }

    /**
     * Processes an incoming validated parcel status change domain event.
     * Enforces provenance authorization, permitted contact boundaries, and atomic PostgreSQL commit.
     *
     * @param event validated domain event envelope
     * @return outcome indicating whether the event was newly persisted or recognized as a duplicate
     */
    public SchedulingResult processLifecycleEvent(ParcelStatusChangedEvent event) {
        Objects.requireNonNull(event, "event cannot be null");
        UUID eventId = event.eventId();
        UUID parcelId = event.payload().parcelId();
        UUID merchantId = event.payload().merchantId();

        // 1. Inbox deduplication pre-check
        if (durableJobPersistenceService.isEventConsumed(consumerGroup, eventId)) {
            log.info("Event already recorded in inbox [consumerGroup={}, eventId={}]. Skipping duplicate.",
                    consumerGroup, eventId);
            return SchedulingResult.duplicate(eventId);
        }

        // 2. Authorize event origin, parcel pairing, and recipient binding with Delivery Operations
        DeliveryEventAuthorization auth = deliveryAuthorizationClient.authorizeEventRecipient(
                eventId,
                parcelId,
                null,
                merchantId,
                false
        );

        if (!auth.isAuthorized()) {
            log.warn("Delivery event rejected by authorization [eventId={}, parcelId={}, reason={}, isStale={}]",
                    eventId, parcelId, auth.authorizationReason(), auth.isStale());
            throw new DeliveryAuthorizationException(
                    "Delivery event authorization rejected: " + auth.authorizationReason());
        }

        String recipientSub = auth.recipientSub();
        if (recipientSub == null || recipientSub.isBlank()) {
            log.error("Authorized delivery event returned blank recipient sub [eventId={}, parcelId={}]",
                    eventId, parcelId);
            throw new DeliveryAuthorizationException("Authorized delivery event contains blank recipient sub");
        }

        // 3. Resolve permitted contact handles from Accounts Service
        Optional<PermittedRecipientContact> contactOpt = accountsContactClient.resolveContact(
                recipientSub,
                NOTIFICATION_PURPOSE,
                NotificationChannel.EMAIL
        );

        if (contactOpt.isEmpty()) {
            log.warn("Recipient reference not found in Accounts [recipientSub={}]", recipientSub);
            throw new RecipientNotFoundException("Recipient not found in Accounts: " + recipientSub);
        }

        PermittedRecipientContact contact = contactOpt.get();
        if (!contact.isEligible() || !contact.hasPermittedEmail()) {
            log.warn("Recipient contact ineligible for EMAIL notifications [recipientSub={}, status={}, reason={}]",
                    recipientSub, contact.status(), contact.rejectionReason());
            throw new NoEligibleNotificationTargetException(
                    "Recipient " + recipientSub + " is not eligible for EMAIL notifications: " + contact.rejectionReason());
        }

        // 4. Construct domain models for atomic persistence
        ConsumedEventId inboxId = new ConsumedEventId(consumerGroup, eventId);
        ConsumedEvent consumedEvent = new ConsumedEvent(
                inboxId,
                event.eventType(),
                event.aggregateVersion().intValue(),
                parcelId,
                merchantId,
                Instant.now()
        );
        consumedEvent.setScheduledAt(Instant.now());

        // Use privacy-preserving opaque destination reference (no raw email in communications_db)
        String destinationRef = "sub:" + recipientSub;
        String template = "PARCEL_" + event.payload().newStatus();

        NotificationJob notificationJob = new NotificationJob(
                UUID.randomUUID(),
                eventId,
                recipientSub,
                parcelId,
                NotificationChannel.EMAIL,
                destinationRef,
                template
        );
        notificationJob.setStatus(NotificationStatus.PENDING);
        notificationJob.setAttempts(0);
        notificationJob.setNextAttemptAt(Instant.now());

        // 5. Persist both inbox receipt and notification job atomically
        try {
            InboxAndNotificationResult result =
                    durableJobPersistenceService.persistInboxAndNotification(consumedEvent, notificationJob);

            log.info("Persisted inbox receipt and notification job [consumerGroup={}, eventId={}, notificationId={}, status={}]",
                    consumerGroup, eventId, notificationJob.getNotificationId(), notificationJob.getStatus());

            return SchedulingResult.persisted(result);

        } catch (DuplicateEventException ex) {
            log.info("Concurrent duplicate detected during persist [consumerGroup={}, eventId={}]. Re-verifying persisted state.",
                    consumerGroup, eventId);
            return SchedulingResult.duplicate(eventId);
        }
    }
}
