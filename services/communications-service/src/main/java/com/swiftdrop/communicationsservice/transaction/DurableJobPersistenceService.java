package com.swiftdrop.communicationsservice.transaction;

import com.swiftdrop.communicationsservice.inbox.ConsumedEvent;
import com.swiftdrop.communicationsservice.inbox.ConsumedEventRepository;
import com.swiftdrop.communicationsservice.notification.NotificationJob;
import com.swiftdrop.communicationsservice.notification.NotificationJobRepository;
import com.swiftdrop.communicationsservice.webhook.WebhookDelivery;
import com.swiftdrop.communicationsservice.webhook.WebhookDeliveryRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class DurableJobPersistenceService {

    private final ConsumedEventRepository consumedEventRepository;
    private final NotificationJobRepository notificationJobRepository;
    private final WebhookDeliveryRepository webhookDeliveryRepository;

    public DurableJobPersistenceService(ConsumedEventRepository consumedEventRepository,
                                        NotificationJobRepository notificationJobRepository,
                                        WebhookDeliveryRepository webhookDeliveryRepository) {
        this.consumedEventRepository = consumedEventRepository;
        this.notificationJobRepository = notificationJobRepository;
        this.webhookDeliveryRepository = webhookDeliveryRepository;
    }

    /**
     * Atomically records an event into the consumer's inbox and enqueues a durable notification job
     * within a single database transaction.
     *
     * @param event The event being consumed.
     * @param job The notification job to persist.
     * @return InboxAndNotificationResult with the persisted records.
     * @throws DuplicateEventException if the event has already been consumed by this consumer group.
     */
    @Transactional
    public InboxAndNotificationResult persistInboxAndNotification(ConsumedEvent event, NotificationJob job) {
        Objects.requireNonNull(event, "ConsumedEvent must not be null");
        Objects.requireNonNull(event.getId(), "ConsumedEventId must not be null");
        Objects.requireNonNull(job, "NotificationJob must not be null");

        String consumerName = event.getConsumerName();
        UUID eventId = event.getEventId();

        if (consumedEventRepository.existsByIdConsumerNameAndIdEventId(consumerName, eventId)) {
            throw new DuplicateEventException(consumerName, eventId);
        }

        ConsumedEvent savedEvent;
        try {
            savedEvent = consumedEventRepository.saveAndFlush(event);
        } catch (DataIntegrityViolationException e) {
            throw new DuplicateEventException(consumerName, eventId);
        }

        NotificationJob savedJob = notificationJobRepository.saveAndFlush(job);
        return new InboxAndNotificationResult(savedEvent, savedJob, true);
    }

    /**
     * Atomically records an event into the consumer's inbox and enqueues durable webhook deliveries
     * for all matching active subscriptions within a single database transaction.
     *
     * @param event The event being consumed.
     * @param deliveries The webhook delivery jobs to persist.
     * @return InboxAndWebhookDeliveriesResult with the persisted records.
     * @throws DuplicateEventException if the event has already been consumed by this consumer group.
     */
    @Transactional
    public InboxAndWebhookDeliveriesResult persistInboxAndWebhookDeliveries(ConsumedEvent event, List<WebhookDelivery> deliveries) {
        Objects.requireNonNull(event, "ConsumedEvent must not be null");
        Objects.requireNonNull(event.getId(), "ConsumedEventId must not be null");
        Objects.requireNonNull(deliveries, "WebhookDeliveries list must not be null");

        String consumerName = event.getConsumerName();
        UUID eventId = event.getEventId();

        if (consumedEventRepository.existsByIdConsumerNameAndIdEventId(consumerName, eventId)) {
            throw new DuplicateEventException(consumerName, eventId);
        }

        ConsumedEvent savedEvent;
        try {
            savedEvent = consumedEventRepository.saveAndFlush(event);
        } catch (DataIntegrityViolationException e) {
            throw new DuplicateEventException(consumerName, eventId);
        }

        List<WebhookDelivery> savedDeliveries = new ArrayList<>();
        for (WebhookDelivery delivery : deliveries) {
            savedDeliveries.add(webhookDeliveryRepository.saveAndFlush(delivery));
        }
        return new InboxAndWebhookDeliveriesResult(savedEvent, savedDeliveries, true);
    }

    /**
     * Checks if an event has already been recorded in the inbox for a specific consumer group.
     */
    @Transactional(readOnly = true)
    public boolean isEventConsumed(String consumerName, UUID eventId) {
        return consumedEventRepository.existsByIdConsumerNameAndIdEventId(consumerName, eventId);
    }

    /**
     * Marks an inbox event as processed once all downstream operations have succeeded.
     */
    @Transactional
    public void markEventProcessed(String consumerName, UUID eventId) {
        consumedEventRepository.findByIdConsumerNameAndIdEventId(consumerName, eventId)
                .ifPresent(event -> {
                    event.setProcessedAt(Instant.now());
                    consumedEventRepository.save(event);
                });
    }
}
