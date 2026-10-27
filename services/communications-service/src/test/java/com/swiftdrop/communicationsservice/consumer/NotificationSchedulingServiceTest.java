package com.swiftdrop.communicationsservice.consumer;

import com.swiftdrop.communicationsservice.client.*;
import com.swiftdrop.communicationsservice.consumer.event.ParcelStatusChangedEvent;
import com.swiftdrop.communicationsservice.consumer.event.ParcelStatusChangedPayload;
import com.swiftdrop.communicationsservice.inbox.ConsumedEvent;
import com.swiftdrop.communicationsservice.inbox.ConsumedEventId;
import com.swiftdrop.communicationsservice.notification.NotificationChannel;
import com.swiftdrop.communicationsservice.notification.NotificationJob;
import com.swiftdrop.communicationsservice.notification.NotificationStatus;
import com.swiftdrop.communicationsservice.transaction.DurableJobPersistenceService;
import com.swiftdrop.communicationsservice.transaction.DuplicateEventException;
import com.swiftdrop.communicationsservice.transaction.InboxAndNotificationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationSchedulingServiceTest {

    @Mock
    private DurableJobPersistenceService durableJobPersistenceService;

    @Mock
    private DeliveryRecipientAuthorizationClient deliveryAuthorizationClient;

    @Mock
    private AccountsRecipientContactClient accountsContactClient;

    private NotificationSchedulingService service;

    private final String consumerGroup = "communications.notifications.v1";
    private UUID eventId;
    private UUID parcelId;
    private UUID merchantId;
    private ParcelStatusChangedEvent sampleEvent;

    @BeforeEach
    void setUp() {
        service = new NotificationSchedulingService(
                durableJobPersistenceService,
                deliveryAuthorizationClient,
                accountsContactClient,
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
                "Colombo-03"
        );

        sampleEvent = new ParcelStatusChangedEvent(
                eventId,
                "parcel.status.changed.v1",
                "1.0.0",
                parcelId,
                "PARCEL",
                3L,
                Instant.now(),
                "corr-1",
                "delivery-operations",
                payload
        );
    }

    @Test
    @DisplayName("Successfully authorize event, resolve permitted contact, and persist inbox and job atomically")
    void testSuccessfulScheduling() {
        String recipientSub = "sub-buyer-123";
        DeliveryEventAuthorization auth = new DeliveryEventAuthorization(
                true,
                parcelId,
                eventId,
                recipientSub,
                merchantId,
                "SWD-20261026-A1B2",
                "DELIVERED",
                "parcel.status.changed.v1",
                3L,
                3L,
                true,
                false,
                "AUTHORIZED"
        );

        PermittedRecipientContact contact = new PermittedRecipientContact(
                recipientSub,
                "buyer@test.test",
                null,
                "Buyer Name",
                true,
                true,
                false,
                "ACTIVE",
                "ELIGIBLE"
        );

        ConsumedEvent persistedEvent = new ConsumedEvent(
                new ConsumedEventId(consumerGroup, eventId),
                "parcel.status.changed.v1",
                3,
                parcelId,
                merchantId,
                Instant.now()
        );
        NotificationJob persistedJob = new NotificationJob(
                UUID.randomUUID(),
                eventId,
                recipientSub,
                parcelId,
                NotificationChannel.EMAIL,
                "sub:" + recipientSub,
                "PARCEL_DELIVERED"
        );

        when(durableJobPersistenceService.isEventConsumed(consumerGroup, eventId)).thenReturn(false);
        when(deliveryAuthorizationClient.authorizeEventRecipient(eventId, parcelId, null, merchantId, false))
                .thenReturn(auth);
        when(accountsContactClient.resolveContact(recipientSub, NotificationSchedulingService.NOTIFICATION_PURPOSE, NotificationChannel.EMAIL))
                .thenReturn(Optional.of(contact));
        when(durableJobPersistenceService.persistInboxAndNotification(any(ConsumedEvent.class), any(NotificationJob.class)))
                .thenReturn(new InboxAndNotificationResult(persistedEvent, persistedJob, true));

        SchedulingResult result = service.processLifecycleEvent(sampleEvent);

        assertThat(result).isNotNull();
        assertThat(result.isDuplicate()).isFalse();
        assertThat(result.newlyPersisted()).isTrue();
        assertThat(result.eventId()).isEqualTo(eventId);

        // Verify captured arguments to ensure strict privacy and persistence invariants
        ArgumentCaptor<ConsumedEvent> eventCaptor = ArgumentCaptor.forClass(ConsumedEvent.class);
        ArgumentCaptor<NotificationJob> jobCaptor = ArgumentCaptor.forClass(NotificationJob.class);
        verify(durableJobPersistenceService).persistInboxAndNotification(eventCaptor.capture(), jobCaptor.capture());

        ConsumedEvent capturedEvent = eventCaptor.getValue();
        assertThat(capturedEvent.getConsumerName()).isEqualTo(consumerGroup);
        assertThat(capturedEvent.getEventId()).isEqualTo(eventId);
        assertThat(capturedEvent.getParcelId()).isEqualTo(parcelId);
        assertThat(capturedEvent.getMerchantId()).isEqualTo(merchantId);

        NotificationJob capturedJob = jobCaptor.getValue();
        assertThat(capturedJob.getEventId()).isEqualTo(eventId);
        assertThat(capturedJob.getParcelId()).isEqualTo(parcelId);
        assertThat(capturedJob.getRecipientSub()).isEqualTo(recipientSub);
        assertThat(capturedJob.getChannel()).isEqualTo(NotificationChannel.EMAIL);
        assertThat(capturedJob.getStatus()).isEqualTo(NotificationStatus.PENDING);
        assertThat(capturedJob.getAttempts()).isZero();
        assertThat(capturedJob.getTemplate()).isEqualTo("PARCEL_DELIVERED");
        // Verify ZERO raw email PII is stored; opaque destination reference is used
        assertThat(capturedJob.getDestinationRef()).isEqualTo("sub:" + recipientSub);
        assertThat(capturedJob.getDestinationRef()).doesNotContain("@");
    }

    @Test
    @DisplayName("Delivery event rejected by authorization throws DeliveryAuthorizationException")
    void testDeliveryUnauthorized_ThrowsDeliveryAuthorizationException() {
        DeliveryEventAuthorization auth = new DeliveryEventAuthorization(
                false,
                parcelId,
                eventId,
                "",
                merchantId,
                "SWD-20261026-A1B2",
                "DELIVERED",
                "parcel.status.changed.v1",
                3L,
                3L,
                true,
                false,
                "PARCEL_NOT_FOUND"
        );

        when(durableJobPersistenceService.isEventConsumed(consumerGroup, eventId)).thenReturn(false);
        when(deliveryAuthorizationClient.authorizeEventRecipient(eventId, parcelId, null, merchantId, false))
                .thenReturn(auth);

        assertThatThrownBy(() -> service.processLifecycleEvent(sampleEvent))
                .isInstanceOf(DeliveryAuthorizationException.class)
                .hasMessageContaining("PARCEL_NOT_FOUND");

        verify(accountsContactClient, never()).resolveContact(any(), any(), any());
        verify(durableJobPersistenceService, never()).persistInboxAndNotification(any(), any());
    }

    @Test
    @DisplayName("Stale Delivery event fails closed with DeliveryAuthorizationException")
    void testDeliveryStaleEvent_ThrowsDeliveryAuthorizationException() {
        DeliveryEventAuthorization auth = new DeliveryEventAuthorization(
                false,
                parcelId,
                eventId,
                "sub-123",
                merchantId,
                "SWD-20261026-A1B2",
                "DELIVERED",
                "parcel.status.changed.v1",
                2L,
                3L,
                true,
                true,
                "STALE_EVENT"
        );

        when(durableJobPersistenceService.isEventConsumed(consumerGroup, eventId)).thenReturn(false);
        when(deliveryAuthorizationClient.authorizeEventRecipient(eventId, parcelId, null, merchantId, false))
                .thenReturn(auth);

        assertThatThrownBy(() -> service.processLifecycleEvent(sampleEvent))
                .isInstanceOf(DeliveryAuthorizationException.class)
                .hasMessageContaining("STALE_EVENT");

        verify(durableJobPersistenceService, never()).persistInboxAndNotification(any(), any());
    }

    @Test
    @DisplayName("Delivery service unavailable propagates exception to fail closed")
    void testDeliveryServiceUnavailable_PropagatesException() {
        when(durableJobPersistenceService.isEventConsumed(consumerGroup, eventId)).thenReturn(false);
        when(deliveryAuthorizationClient.authorizeEventRecipient(eventId, parcelId, null, merchantId, false))
                .thenThrow(new DeliveryServiceUnavailableException("Connection timeout"));

        assertThatThrownBy(() -> service.processLifecycleEvent(sampleEvent))
                .isInstanceOf(DeliveryServiceUnavailableException.class);

        verify(durableJobPersistenceService, never()).persistInboxAndNotification(any(), any());
    }

    @Test
    @DisplayName("Delivery security exception propagates exception to fail closed")
    void testDeliverySecurityException_PropagatesException() {
        when(durableJobPersistenceService.isEventConsumed(consumerGroup, eventId)).thenReturn(false);
        when(deliveryAuthorizationClient.authorizeEventRecipient(eventId, parcelId, null, merchantId, false))
                .thenThrow(new DeliverySecurityException("Caller unauthorized"));

        assertThatThrownBy(() -> service.processLifecycleEvent(sampleEvent))
                .isInstanceOf(DeliverySecurityException.class);

        verify(durableJobPersistenceService, never()).persistInboxAndNotification(any(), any());
    }

    @Test
    @DisplayName("Authorized Delivery event with blank recipient sub throws DeliveryAuthorizationException")
    void testDeliveryAuthorizedBlankRecipient_ThrowsDeliveryAuthorizationException() {
        DeliveryEventAuthorization auth = new DeliveryEventAuthorization(
                true,
                parcelId,
                eventId,
                "   ",
                merchantId,
                "SWD-20261026-A1B2",
                "DELIVERED",
                "parcel.status.changed.v1",
                3L,
                3L,
                true,
                false,
                "AUTHORIZED"
        );

        when(durableJobPersistenceService.isEventConsumed(consumerGroup, eventId)).thenReturn(false);
        when(deliveryAuthorizationClient.authorizeEventRecipient(eventId, parcelId, null, merchantId, false))
                .thenReturn(auth);

        assertThatThrownBy(() -> service.processLifecycleEvent(sampleEvent))
                .isInstanceOf(DeliveryAuthorizationException.class)
                .hasMessageContaining("blank recipient sub");

        verify(durableJobPersistenceService, never()).persistInboxAndNotification(any(), any());
    }

    @Test
    @DisplayName("Accounts recipient not found throws RecipientNotFoundException")
    void testAccountsRecipientNotFound_ThrowsRecipientNotFoundException() {
        String recipientSub = "sub-buyer-123";
        DeliveryEventAuthorization auth = new DeliveryEventAuthorization(
                true,
                parcelId,
                eventId,
                recipientSub,
                merchantId,
                "SWD-20261026-A1B2",
                "DELIVERED",
                "parcel.status.changed.v1",
                3L,
                3L,
                true,
                false,
                "AUTHORIZED"
        );

        when(durableJobPersistenceService.isEventConsumed(consumerGroup, eventId)).thenReturn(false);
        when(deliveryAuthorizationClient.authorizeEventRecipient(eventId, parcelId, null, merchantId, false))
                .thenReturn(auth);
        when(accountsContactClient.resolveContact(recipientSub, NotificationSchedulingService.NOTIFICATION_PURPOSE, NotificationChannel.EMAIL))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.processLifecycleEvent(sampleEvent))
                .isInstanceOf(RecipientNotFoundException.class)
                .hasMessageContaining(recipientSub);

        verify(durableJobPersistenceService, never()).persistInboxAndNotification(any(), any());
    }

    @Test
    @DisplayName("Accounts recipient contact ineligible throws NoEligibleNotificationTargetException")
    void testAccountsContactIneligible_ThrowsNoEligibleNotificationTargetException() {
        String recipientSub = "sub-buyer-123";
        DeliveryEventAuthorization auth = new DeliveryEventAuthorization(
                true,
                parcelId,
                eventId,
                recipientSub,
                merchantId,
                "SWD-20261026-A1B2",
                "DELIVERED",
                "parcel.status.changed.v1",
                3L,
                3L,
                true,
                false,
                "AUTHORIZED"
        );

        PermittedRecipientContact contact = new PermittedRecipientContact(
                recipientSub,
                "buyer@test.test",
                null,
                "Buyer Name",
                false,
                true,
                false,
                "SUSPENDED",
                "STAFF_SUSPENDED"
        );

        when(durableJobPersistenceService.isEventConsumed(consumerGroup, eventId)).thenReturn(false);
        when(deliveryAuthorizationClient.authorizeEventRecipient(eventId, parcelId, null, merchantId, false))
                .thenReturn(auth);
        when(accountsContactClient.resolveContact(recipientSub, NotificationSchedulingService.NOTIFICATION_PURPOSE, NotificationChannel.EMAIL))
                .thenReturn(Optional.of(contact));

        assertThatThrownBy(() -> service.processLifecycleEvent(sampleEvent))
                .isInstanceOf(NoEligibleNotificationTargetException.class)
                .hasMessageContaining("STAFF_SUSPENDED");

        verify(durableJobPersistenceService, never()).persistInboxAndNotification(any(), any());
    }

    @Test
    @DisplayName("Accounts recipient with email not permitted throws NoEligibleNotificationTargetException")
    void testAccountsEmailNotPermitted_ThrowsNoEligibleNotificationTargetException() {
        String recipientSub = "sub-buyer-123";
        DeliveryEventAuthorization auth = new DeliveryEventAuthorization(
                true,
                parcelId,
                eventId,
                recipientSub,
                merchantId,
                "SWD-20261026-A1B2",
                "DELIVERED",
                "parcel.status.changed.v1",
                3L,
                3L,
                true,
                false,
                "AUTHORIZED"
        );

        PermittedRecipientContact contact = new PermittedRecipientContact(
                recipientSub,
                "buyer@test.test",
                null,
                "Buyer Name",
                true,
                false,
                false,
                "ACTIVE",
                "EMAIL_NOT_PERMITTED"
        );

        when(durableJobPersistenceService.isEventConsumed(consumerGroup, eventId)).thenReturn(false);
        when(deliveryAuthorizationClient.authorizeEventRecipient(eventId, parcelId, null, merchantId, false))
                .thenReturn(auth);
        when(accountsContactClient.resolveContact(recipientSub, NotificationSchedulingService.NOTIFICATION_PURPOSE, NotificationChannel.EMAIL))
                .thenReturn(Optional.of(contact));

        assertThatThrownBy(() -> service.processLifecycleEvent(sampleEvent))
                .isInstanceOf(NoEligibleNotificationTargetException.class);

        verify(durableJobPersistenceService, never()).persistInboxAndNotification(any(), any());
    }

    @Test
    @DisplayName("Accounts service unavailable propagates exception to fail closed")
    void testAccountsServiceUnavailable_PropagatesException() {
        String recipientSub = "sub-buyer-123";
        DeliveryEventAuthorization auth = new DeliveryEventAuthorization(
                true,
                parcelId,
                eventId,
                recipientSub,
                merchantId,
                "SWD-20261026-A1B2",
                "DELIVERED",
                "parcel.status.changed.v1",
                3L,
                3L,
                true,
                false,
                "AUTHORIZED"
        );

        when(durableJobPersistenceService.isEventConsumed(consumerGroup, eventId)).thenReturn(false);
        when(deliveryAuthorizationClient.authorizeEventRecipient(eventId, parcelId, null, merchantId, false))
                .thenReturn(auth);
        when(accountsContactClient.resolveContact(recipientSub, NotificationSchedulingService.NOTIFICATION_PURPOSE, NotificationChannel.EMAIL))
                .thenThrow(new AccountsServiceUnavailableException("Accounts service unavailable"));

        assertThatThrownBy(() -> service.processLifecycleEvent(sampleEvent))
                .isInstanceOf(AccountsServiceUnavailableException.class);

        verify(durableJobPersistenceService, never()).persistInboxAndNotification(any(), any());
    }

    @Test
    @DisplayName("Duplicate event already in inbox is recognized without external calls or new job")
    void testDuplicateEvent_AlreadyInInbox_ReturnsDuplicateWithoutNewJob() {
        when(durableJobPersistenceService.isEventConsumed(consumerGroup, eventId)).thenReturn(true);

        SchedulingResult result = service.processLifecycleEvent(sampleEvent);

        assertThat(result).isNotNull();
        assertThat(result.isDuplicate()).isTrue();
        assertThat(result.newlyPersisted()).isFalse();

        verify(deliveryAuthorizationClient, never()).authorizeEventRecipient(any(), any(), any(), any(), anyBoolean());
        verify(accountsContactClient, never()).resolveContact(any(), any(), any());
        verify(durableJobPersistenceService, never()).persistInboxAndNotification(any(), any());
    }

    @Test
    @DisplayName("Duplicate event detected on concurrent insert is handled safely")
    void testDuplicateEvent_RaceCondition_ReturnsDuplicate() {
        String recipientSub = "sub-buyer-123";
        DeliveryEventAuthorization auth = new DeliveryEventAuthorization(
                true,
                parcelId,
                eventId,
                recipientSub,
                merchantId,
                "SWD-20261026-A1B2",
                "DELIVERED",
                "parcel.status.changed.v1",
                3L,
                3L,
                true,
                false,
                "AUTHORIZED"
        );
        PermittedRecipientContact contact = new PermittedRecipientContact(
                recipientSub,
                "buyer@test.test",
                null,
                "Buyer Name",
                true,
                true,
                false,
                "ACTIVE",
                "ELIGIBLE"
        );

        when(durableJobPersistenceService.isEventConsumed(consumerGroup, eventId)).thenReturn(false);
        when(deliveryAuthorizationClient.authorizeEventRecipient(eventId, parcelId, null, merchantId, false))
                .thenReturn(auth);
        when(accountsContactClient.resolveContact(recipientSub, NotificationSchedulingService.NOTIFICATION_PURPOSE, NotificationChannel.EMAIL))
                .thenReturn(Optional.of(contact));
        when(durableJobPersistenceService.persistInboxAndNotification(any(ConsumedEvent.class), any(NotificationJob.class)))
                .thenThrow(new DuplicateEventException(consumerGroup, eventId));

        SchedulingResult result = service.processLifecycleEvent(sampleEvent);

        assertThat(result).isNotNull();
        assertThat(result.isDuplicate()).isTrue();
        assertThat(result.newlyPersisted()).isFalse();
    }

    @Test
    @DisplayName("Database failure during persistence propagates exception and does not claim duplicate")
    void testDatabaseError_PropagatesException() {
        String recipientSub = "sub-buyer-123";
        DeliveryEventAuthorization auth = new DeliveryEventAuthorization(
                true,
                parcelId,
                eventId,
                recipientSub,
                merchantId,
                "SWD-20261026-A1B2",
                "DELIVERED",
                "parcel.status.changed.v1",
                3L,
                3L,
                true,
                false,
                "AUTHORIZED"
        );
        PermittedRecipientContact contact = new PermittedRecipientContact(
                recipientSub,
                "buyer@test.test",
                null,
                "Buyer Name",
                true,
                true,
                false,
                "ACTIVE",
                "ELIGIBLE"
        );

        when(durableJobPersistenceService.isEventConsumed(consumerGroup, eventId)).thenReturn(false);
        when(deliveryAuthorizationClient.authorizeEventRecipient(eventId, parcelId, null, merchantId, false))
                .thenReturn(auth);
        when(accountsContactClient.resolveContact(recipientSub, NotificationSchedulingService.NOTIFICATION_PURPOSE, NotificationChannel.EMAIL))
                .thenReturn(Optional.of(contact));
        when(durableJobPersistenceService.persistInboxAndNotification(any(ConsumedEvent.class), any(NotificationJob.class)))
                .thenThrow(new DataIntegrityViolationException("check constraint violated"));

        assertThatThrownBy(() -> service.processLifecycleEvent(sampleEvent))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
