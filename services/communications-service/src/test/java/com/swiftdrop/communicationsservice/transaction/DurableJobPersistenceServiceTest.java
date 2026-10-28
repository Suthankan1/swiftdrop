package com.swiftdrop.communicationsservice.transaction;

import com.swiftdrop.communicationsservice.inbox.ConsumedEvent;
import com.swiftdrop.communicationsservice.inbox.ConsumedEventId;
import com.swiftdrop.communicationsservice.inbox.ConsumedEventRepository;
import com.swiftdrop.communicationsservice.notification.NotificationChannel;
import com.swiftdrop.communicationsservice.notification.NotificationJob;
import com.swiftdrop.communicationsservice.notification.NotificationJobRepository;
import com.swiftdrop.communicationsservice.notification.NotificationStatus;
import com.swiftdrop.communicationsservice.webhook.WebhookDelivery;
import com.swiftdrop.communicationsservice.webhook.WebhookDeliveryRepository;
import com.swiftdrop.communicationsservice.webhook.WebhookDeliveryStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DurableJobPersistenceServiceTest {

    @Mock
    private ConsumedEventRepository consumedEventRepository;

    @Mock
    private NotificationJobRepository notificationJobRepository;

    @Mock
    private WebhookDeliveryRepository webhookDeliveryRepository;

    private DurableJobPersistenceService service;

    @BeforeEach
    void setUp() {
        service = new DurableJobPersistenceService(
                consumedEventRepository,
                notificationJobRepository,
                webhookDeliveryRepository
        );
    }

    @Test
    @DisplayName("Successfully persist inbox receipt and notification job atomically")
    void persistInboxAndNotification_Success() {
        UUID eventId = UUID.randomUUID();
        UUID parcelId = UUID.randomUUID();
        String consumer = "communications.notifications.v1";
        ConsumedEventId id = new ConsumedEventId(consumer, eventId);
        ConsumedEvent event = new ConsumedEvent(id, "parcel.status.changed.v1", 1, parcelId, null, Instant.now());
        NotificationJob job = new NotificationJob(UUID.randomUUID(), eventId, "sub-123", parcelId,
                NotificationChannel.EMAIL, "recipient@test.test", "PARCEL_DELIVERED");

        when(consumedEventRepository.existsByIdConsumerNameAndIdEventId(consumer, eventId)).thenReturn(false);
        when(consumedEventRepository.saveAndFlush(any(ConsumedEvent.class))).thenReturn(event);
        when(notificationJobRepository.saveAndFlush(any(NotificationJob.class))).thenReturn(job);

        InboxAndNotificationResult result = service.persistInboxAndNotification(event, job);

        assertThat(result).isNotNull();
        assertThat(result.newlyPersisted()).isTrue();
        assertThat(result.consumedEvent()).isEqualTo(event);
        assertThat(result.notificationJob()).isEqualTo(job);

        verify(consumedEventRepository).saveAndFlush(event);
        verify(notificationJobRepository).saveAndFlush(job);
    }

    @Test
    @DisplayName("Duplicate event for same consumer group throws DuplicateEventException")
    void persistInboxAndNotification_DuplicateEvent_ThrowsException() {
        UUID eventId = UUID.randomUUID();
        UUID parcelId = UUID.randomUUID();
        String consumer = "communications.notifications.v1";
        ConsumedEventId id = new ConsumedEventId(consumer, eventId);
        ConsumedEvent event = new ConsumedEvent(id, "parcel.status.changed.v1", 1, parcelId, null, Instant.now());
        NotificationJob job = new NotificationJob(UUID.randomUUID(), eventId, "sub-123", parcelId,
                NotificationChannel.EMAIL, "recipient@test.test", "PARCEL_DELIVERED");

        when(consumedEventRepository.existsByIdConsumerNameAndIdEventId(consumer, eventId)).thenReturn(true);

        assertThatThrownBy(() -> service.persistInboxAndNotification(event, job))
                .isInstanceOf(DuplicateEventException.class)
                .hasMessageContaining(consumer)
                .hasMessageContaining(eventId.toString());

        verify(consumedEventRepository, never()).saveAndFlush(any());
        verify(notificationJobRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Data integrity violation on save is translated to DuplicateEventException")
    void persistInboxAndNotification_DataIntegrityViolation_ThrowsDuplicateEventException() {
        UUID eventId = UUID.randomUUID();
        UUID parcelId = UUID.randomUUID();
        String consumer = "communications.notifications.v1";
        ConsumedEventId id = new ConsumedEventId(consumer, eventId);
        ConsumedEvent event = new ConsumedEvent(id, "parcel.status.changed.v1", 1, parcelId, null, Instant.now());
        NotificationJob job = new NotificationJob(UUID.randomUUID(), eventId, "sub-123", parcelId,
                NotificationChannel.EMAIL, "recipient@test.test", "PARCEL_DELIVERED");

        when(consumedEventRepository.existsByIdConsumerNameAndIdEventId(consumer, eventId)).thenReturn(false);
        when(consumedEventRepository.saveAndFlush(any(ConsumedEvent.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

        assertThatThrownBy(() -> service.persistInboxAndNotification(event, job))
                .isInstanceOf(DuplicateEventException.class);
    }

    @Test
    @DisplayName("Notification job data integrity violation rethrows DataIntegrityViolationException and is not misclassified as duplicate")
    void persistInboxAndNotification_JobDataIntegrityViolation_RethrowsDataIntegrityViolationException() {
        UUID eventId = UUID.randomUUID();
        UUID parcelId = UUID.randomUUID();
        String consumer = "communications.notifications.v1";
        ConsumedEventId id = new ConsumedEventId(consumer, eventId);
        ConsumedEvent event = new ConsumedEvent(id, "parcel.status.changed.v1", 1, parcelId, null, Instant.now());
        NotificationJob job = new NotificationJob(UUID.randomUUID(), eventId, "sub-123", parcelId,
                NotificationChannel.EMAIL, "recipient@test.test", "PARCEL_DELIVERED");

        when(consumedEventRepository.existsByIdConsumerNameAndIdEventId(consumer, eventId)).thenReturn(false);
        when(consumedEventRepository.saveAndFlush(any(ConsumedEvent.class))).thenReturn(event);
        when(notificationJobRepository.saveAndFlush(any(NotificationJob.class)))
                .thenThrow(new DataIntegrityViolationException("check constraint failed"));

        assertThatThrownBy(() -> service.persistInboxAndNotification(event, job))
                .isInstanceOf(DataIntegrityViolationException.class)
                .isNotInstanceOf(DuplicateEventException.class);
    }

    @Test
    @DisplayName("Successfully persist inbox receipt and webhook deliveries atomically")
    void persistInboxAndWebhookDeliveries_Success() {
        UUID eventId = UUID.randomUUID();
        UUID parcelId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        String consumer = "communications.webhooks.v1";
        ConsumedEventId id = new ConsumedEventId(consumer, eventId);
        ConsumedEvent event = new ConsumedEvent(id, "parcel.status.changed.v1", 2, parcelId, merchantId, Instant.now());

        WebhookDelivery delivery1 = new WebhookDelivery(UUID.randomUUID(), UUID.randomUUID(), eventId, parcelId,
                merchantId, 2, "digest1");
        WebhookDelivery delivery2 = new WebhookDelivery(UUID.randomUUID(), UUID.randomUUID(), eventId, parcelId,
                merchantId, 2, "digest2");
        List<WebhookDelivery> deliveries = List.of(delivery1, delivery2);

        when(consumedEventRepository.existsByIdConsumerNameAndIdEventId(consumer, eventId)).thenReturn(false);
        when(consumedEventRepository.saveAndFlush(any(ConsumedEvent.class))).thenReturn(event);
        when(webhookDeliveryRepository.saveAndFlush(delivery1)).thenReturn(delivery1);
        when(webhookDeliveryRepository.saveAndFlush(delivery2)).thenReturn(delivery2);

        InboxAndWebhookDeliveriesResult result = service.persistInboxAndWebhookDeliveries(event, deliveries);

        assertThat(result).isNotNull();
        assertThat(result.newlyPersisted()).isTrue();
        assertThat(result.consumedEvent()).isEqualTo(event);
        assertThat(result.webhookDeliveries()).containsExactly(delivery1, delivery2);

        verify(consumedEventRepository).saveAndFlush(event);
        verify(webhookDeliveryRepository, times(2)).saveAndFlush(any(WebhookDelivery.class));
    }

    @Test
    @DisplayName("Duplicate event on webhook persistence throws DuplicateEventException")
    void persistInboxAndWebhookDeliveries_Duplicate_ThrowsException() {
        UUID eventId = UUID.randomUUID();
        UUID parcelId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        String consumer = "communications.webhooks.v1";
        ConsumedEventId id = new ConsumedEventId(consumer, eventId);
        ConsumedEvent event = new ConsumedEvent(id, "parcel.status.changed.v1", 2, parcelId, merchantId, Instant.now());
        List<WebhookDelivery> deliveries = List.of(
                new WebhookDelivery(UUID.randomUUID(), UUID.randomUUID(), eventId, parcelId, merchantId, 2, "digest")
        );

        when(consumedEventRepository.existsByIdConsumerNameAndIdEventId(consumer, eventId)).thenReturn(true);

        assertThatThrownBy(() -> service.persistInboxAndWebhookDeliveries(event, deliveries))
                .isInstanceOf(DuplicateEventException.class);

        verify(consumedEventRepository, never()).saveAndFlush(any());
        verify(webhookDeliveryRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("isEventConsumed checks existence by consumer name and event ID")
    void isEventConsumed_ChecksCorrectParameters() {
        UUID eventId = UUID.randomUUID();
        String consumer = "communications.webhooks.v1";

        when(consumedEventRepository.existsByIdConsumerNameAndIdEventId(consumer, eventId)).thenReturn(true);

        boolean consumed = service.isEventConsumed(consumer, eventId);
        assertThat(consumed).isTrue();

        verify(consumedEventRepository).existsByIdConsumerNameAndIdEventId(consumer, eventId);
    }

    @Test
    @DisplayName("markEventProcessed updates processedAt timestamp")
    void markEventProcessed_UpdatesTimestamp() {
        UUID eventId = UUID.randomUUID();
        String consumer = "communications.notifications.v1";
        ConsumedEventId id = new ConsumedEventId(consumer, eventId);
        ConsumedEvent event = new ConsumedEvent(id, "parcel.status.changed.v1", 1, UUID.randomUUID(), null, Instant.now());

        when(consumedEventRepository.findByIdConsumerNameAndIdEventId(consumer, eventId))
                .thenReturn(Optional.of(event));

        service.markEventProcessed(consumer, eventId);

        assertThat(event.getProcessedAt()).isNotNull();
        verify(consumedEventRepository).save(event);
    }

    @Test
    @DisplayName("Null parameters are safely rejected with NullPointerException")
    void nullParameters_ThrowNullPointerException() {
        assertThatThrownBy(() -> service.persistInboxAndNotification(null, mock(NotificationJob.class)))
                .isInstanceOf(NullPointerException.class);

        ConsumedEvent eventWithoutId = new ConsumedEvent();
        assertThatThrownBy(() -> service.persistInboxAndNotification(eventWithoutId, mock(NotificationJob.class)))
                .isInstanceOf(NullPointerException.class);

        ConsumedEvent validEvent = new ConsumedEvent(new ConsumedEventId("c", UUID.randomUUID()), "t", 1, UUID.randomUUID(), null, Instant.now());
        assertThatThrownBy(() -> service.persistInboxAndNotification(validEvent, null))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> service.persistInboxAndWebhookDeliveries(null, List.of()))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> service.persistInboxAndWebhookDeliveries(validEvent, null))
                .isInstanceOf(NullPointerException.class);
    }
}
