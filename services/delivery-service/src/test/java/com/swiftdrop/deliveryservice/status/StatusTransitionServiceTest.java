package com.swiftdrop.deliveryservice.status;

import com.swiftdrop.deliveryservice.assignment.CourierAssignmentContract;
import com.swiftdrop.deliveryservice.core.Parcel;
import com.swiftdrop.deliveryservice.core.ParcelNotFoundException;
import com.swiftdrop.deliveryservice.core.ParcelRepository;
import com.swiftdrop.deliveryservice.core.ParcelStatus;
import com.swiftdrop.deliveryservice.status.dto.ParcelStatusChangeResponse;
import com.swiftdrop.deliveryservice.status.dto.UpdateStatusRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StatusTransitionServiceTest {

    @Mock
    private ParcelRepository parcelRepository;

    @Mock
    private StatusHistoryRepository statusHistoryRepository;

    @Mock
    private CourierAssignmentContract courierAssignmentContract;

    private StatusTransitionService serviceWithAssignment;
    private StatusTransitionService serviceWithoutAssignment;

    private final UUID parcelId = UUID.randomUUID();
    private final String courierSub = "courier-sub-101";
    private final String trackingNumber = "SWD-20261025-0001";

    @BeforeEach
    void setUp() {
        serviceWithAssignment = new StatusTransitionService(
                parcelRepository,
                statusHistoryRepository,
                courierAssignmentContract
        );
        serviceWithoutAssignment = new StatusTransitionService(
                parcelRepository,
                statusHistoryRepository,
                null // Simulates production runtime where Issue #24 bean is absent
        );
    }

    private Parcel createParcel(ParcelStatus status, Long version) {
        Parcel parcel = new Parcel(
                parcelId,
                trackingNumber,
                "sender-1",
                UUID.randomUUID(),
                UUID.randomUUID(),
                "v1",
                100000L,
                "LKR",
                new BigDecimal("2.000")
        );
        parcel.setStatus(status);
        parcel.setVersion(version);
        return parcel;
    }

    @Test
    @DisplayName("1. Permitted transition CREATED -> PICKED_UP succeeds for assigned courier")
    void testPermittedTransitionForAssignedCourier() {
        Parcel parcel = createParcel(ParcelStatus.CREATED, 1L);
        when(parcelRepository.findById(parcelId)).thenReturn(Optional.of(parcel));
        when(courierAssignmentContract.hasActiveAssignment(parcelId, courierSub)).thenReturn(true);
        when(parcelRepository.saveAndFlush(any(Parcel.class))).thenAnswer(invocation -> {
            Parcel p = invocation.getArgument(0);
            p.setVersion(2L);
            return p;
        });

        UpdateStatusRequest request = new UpdateStatusRequest(
                ParcelStatus.PICKED_UP,
                1L,
                "Package collected from sender warehouse.",
                null,
                null
        );

        ParcelStatusChangeResponse response = serviceWithAssignment.updateStatus(
                parcelId,
                request,
                courierSub,
                List.of("ROLE_COURIER")
        );

        assertThat(response.parcelId()).isEqualTo(parcelId);
        assertThat(response.trackingNumber()).isEqualTo(trackingNumber);
        assertThat(response.previousStatus()).isEqualTo("CREATED");
        assertThat(response.currentStatus()).isEqualTo("PICKED_UP");
        assertThat(response.stateVersion()).isEqualTo(2L);
        assertThat(response.updatedParcelVersion()).isEqualTo(2L);
        assertThat(response.occurredAt()).isNotNull();

        // Verify StatusHistory was captured and persisted
        ArgumentCaptor<StatusHistory> historyCaptor = ArgumentCaptor.forClass(StatusHistory.class);
        verify(statusHistoryRepository).save(historyCaptor.capture());
        StatusHistory savedHistory = historyCaptor.getValue();
        assertThat(savedHistory.getParcelId()).isEqualTo(parcelId);
        assertThat(savedHistory.getPreviousStatus()).isEqualTo(ParcelStatus.CREATED);
        assertThat(savedHistory.getNewStatus()).isEqualTo(ParcelStatus.PICKED_UP);
        assertThat(savedHistory.getActorSub()).isEqualTo(courierSub);
        assertThat(savedHistory.getResultingVersion()).isEqualTo(2L);
        assertThat(savedHistory.getNote()).isEqualTo("Package collected from sender warehouse.");
    }

    @Test
    @DisplayName("2. Permitted transition PICKED_UP -> IN_TRANSIT succeeds")
    void testPermittedTransitionInTransit() {
        Parcel parcel = createParcel(ParcelStatus.PICKED_UP, 2L);
        when(parcelRepository.findById(parcelId)).thenReturn(Optional.of(parcel));
        when(courierAssignmentContract.hasActiveAssignment(parcelId, courierSub)).thenReturn(true);
        when(parcelRepository.saveAndFlush(any(Parcel.class))).thenAnswer(invocation -> {
            Parcel p = invocation.getArgument(0);
            p.setVersion(3L);
            return p;
        });

        UpdateStatusRequest request = new UpdateStatusRequest(
                ParcelStatus.IN_TRANSIT,
                2L,
                "En route to sorting facility.",
                null,
                null
        );

        ParcelStatusChangeResponse response = serviceWithAssignment.updateStatus(
                parcelId,
                request,
                courierSub,
                List.of("ROLE_COURIER")
        );

        assertThat(response.previousStatus()).isEqualTo("PICKED_UP");
        assertThat(response.currentStatus()).isEqualTo("IN_TRANSIT");
        assertThat(response.stateVersion()).isEqualTo(3L);
    }

    @Test
    @DisplayName("3. Permitted transition IN_TRANSIT -> OUT_FOR_DELIVERY succeeds")
    void testPermittedTransitionOutForDelivery() {
        Parcel parcel = createParcel(ParcelStatus.IN_TRANSIT, 3L);
        when(parcelRepository.findById(parcelId)).thenReturn(Optional.of(parcel));
        when(courierAssignmentContract.hasActiveAssignment(parcelId, courierSub)).thenReturn(true);
        when(parcelRepository.saveAndFlush(any(Parcel.class))).thenAnswer(invocation -> {
            Parcel p = invocation.getArgument(0);
            p.setVersion(4L);
            return p;
        });

        UpdateStatusRequest request = new UpdateStatusRequest(
                ParcelStatus.OUT_FOR_DELIVERY,
                3L,
                "Courier out for delivery.",
                null,
                null
        );

        ParcelStatusChangeResponse response = serviceWithAssignment.updateStatus(
                parcelId,
                request,
                courierSub,
                List.of("ROLE_COURIER")
        );

        assertThat(response.previousStatus()).isEqualTo("IN_TRANSIT");
        assertThat(response.currentStatus()).isEqualTo("OUT_FOR_DELIVERY");
        assertThat(response.stateVersion()).isEqualTo(4L);
    }

    @Test
    @DisplayName("4. Permitted transition to FAILED with approved reason succeeds")
    void testPermittedTransitionFailedWithReason() {
        Parcel parcel = createParcel(ParcelStatus.OUT_FOR_DELIVERY, 4L);
        when(parcelRepository.findById(parcelId)).thenReturn(Optional.of(parcel));
        when(courierAssignmentContract.hasActiveAssignment(parcelId, courierSub)).thenReturn(true);
        when(parcelRepository.saveAndFlush(any(Parcel.class))).thenAnswer(invocation -> {
            Parcel p = invocation.getArgument(0);
            p.setVersion(5L);
            return p;
        });

        UpdateStatusRequest request = new UpdateStatusRequest(
                ParcelStatus.FAILED,
                4L,
                "Recipient could not be contacted at destination address.",
                ParcelLifecycleTransitionContract.REASON_RECIPIENT_UNAVAILABLE,
                null
        );

        ParcelStatusChangeResponse response = serviceWithAssignment.updateStatus(
                parcelId,
                request,
                courierSub,
                List.of("ROLE_COURIER")
        );

        assertThat(response.currentStatus()).isEqualTo("FAILED");
        assertThat(response.stateVersion()).isEqualTo(5L);

        ArgumentCaptor<StatusHistory> historyCaptor = ArgumentCaptor.forClass(StatusHistory.class);
        verify(statusHistoryRepository).save(historyCaptor.capture());
        assertThat(historyCaptor.getValue().getReason()).isEqualTo(ParcelLifecycleTransitionContract.REASON_RECIPIENT_UNAVAILABLE);
    }

    @Test
    @DisplayName("5. Issue #24 Assignment Gate: Courier status transition fails-closed when provider bean is missing")
    void testAssignmentGateMissingProviderFailsClosed() {
        Parcel parcel = createParcel(ParcelStatus.CREATED, 1L);
        when(parcelRepository.findById(parcelId)).thenReturn(Optional.of(parcel));

        UpdateStatusRequest request = new UpdateStatusRequest(
                ParcelStatus.PICKED_UP,
                1L,
                null,
                null,
                null
        );

        assertThatThrownBy(() -> serviceWithoutAssignment.updateStatus(
                parcelId,
                request,
                courierSub,
                List.of("ROLE_COURIER")
        )).isInstanceOf(StatusAccessDeniedException.class)
                .hasMessageContaining("blocked pending Issue #24");

        verifyNoInteractions(statusHistoryRepository);
    }

    @Test
    @DisplayName("6. Issue #24 Assignment Gate: Unassigned courier is rejected with StatusAccessDeniedException")
    void testUnassignedCourierIsDenied() {
        Parcel parcel = createParcel(ParcelStatus.CREATED, 1L);
        when(parcelRepository.findById(parcelId)).thenReturn(Optional.of(parcel));
        when(courierAssignmentContract.hasActiveAssignment(parcelId, courierSub)).thenReturn(false);

        UpdateStatusRequest request = new UpdateStatusRequest(
                ParcelStatus.PICKED_UP,
                1L,
                null,
                null,
                null
        );

        assertThatThrownBy(() -> serviceWithAssignment.updateStatus(
                parcelId,
                request,
                courierSub,
                List.of("ROLE_COURIER")
        )).isInstanceOf(StatusAccessDeniedException.class)
                .hasMessageContaining("not actively assigned");

        verifyNoInteractions(statusHistoryRepository);
    }

    @Test
    @DisplayName("7. Actor with unapproved role (e.g. CUSTOMER) is denied")
    void testUnapprovedRoleIsDenied() {
        Parcel parcel = createParcel(ParcelStatus.CREATED, 1L);
        when(parcelRepository.findById(parcelId)).thenReturn(Optional.of(parcel));

        UpdateStatusRequest request = new UpdateStatusRequest(
                ParcelStatus.PICKED_UP,
                1L,
                null,
                null,
                null
        );

        assertThatThrownBy(() -> serviceWithAssignment.updateStatus(
                parcelId,
                request,
                "customer-sub-1",
                List.of("ROLE_CUSTOMER")
        )).isInstanceOf(StatusAccessDeniedException.class)
                .hasMessageContaining("lacks authorized role");

        verifyNoInteractions(statusHistoryRepository);
    }

    @Test
    @DisplayName("8. Optimistic concurrency mismatch throws VersionConflictException")
    void testOptimisticConcurrencyConflict() {
        Parcel parcel = createParcel(ParcelStatus.CREATED, 2L); // current version is 2
        when(parcelRepository.findById(parcelId)).thenReturn(Optional.of(parcel));
        when(courierAssignmentContract.hasActiveAssignment(parcelId, courierSub)).thenReturn(true);

        UpdateStatusRequest request = new UpdateStatusRequest(
                ParcelStatus.PICKED_UP,
                1L, // stale expectedVersion 1
                null,
                null,
                null
        );

        assertThatThrownBy(() -> serviceWithAssignment.updateStatus(
                parcelId,
                request,
                courierSub,
                List.of("ROLE_COURIER")
        )).isInstanceOf(VersionConflictException.class)
                .hasMessageContaining("expected version 1 but was 2");

        verify(parcelRepository, never()).saveAndFlush(any());
        verifyNoInteractions(statusHistoryRepository);
    }

    @Test
    @DisplayName("9. Illegal backward transition throws IllegalTransitionException")
    void testIllegalBackwardTransition() {
        Parcel parcel = createParcel(ParcelStatus.IN_TRANSIT, 2L);
        when(parcelRepository.findById(parcelId)).thenReturn(Optional.of(parcel));
        when(courierAssignmentContract.hasActiveAssignment(parcelId, courierSub)).thenReturn(true);

        UpdateStatusRequest request = new UpdateStatusRequest(
                ParcelStatus.PICKED_UP, // Backward transition
                2L,
                null,
                null,
                null
        );

        assertThatThrownBy(() -> serviceWithAssignment.updateStatus(
                parcelId,
                request,
                courierSub,
                List.of("ROLE_COURIER")
        )).isInstanceOf(IllegalTransitionException.class)
                .hasMessageContaining("Illegal transition");

        verify(parcelRepository, never()).saveAndFlush(any());
        verifyNoInteractions(statusHistoryRepository);
    }

    @Test
    @DisplayName("10. Mutation from terminal DELIVERED throws TerminalStateMutationException")
    void testMutationFromTerminalDelivered() {
        Parcel parcel = createParcel(ParcelStatus.DELIVERED, 5L);
        when(parcelRepository.findById(parcelId)).thenReturn(Optional.of(parcel));
        when(courierAssignmentContract.hasActiveAssignment(parcelId, courierSub)).thenReturn(true);

        UpdateStatusRequest request = new UpdateStatusRequest(
                ParcelStatus.OUT_FOR_DELIVERY,
                5L,
                null,
                null,
                null
        );

        assertThatThrownBy(() -> serviceWithAssignment.updateStatus(
                parcelId,
                request,
                courierSub,
                List.of("ROLE_COURIER")
        )).isInstanceOf(TerminalStateMutationException.class)
                .hasMessageContaining("Cannot transition from terminal state");

        verify(parcelRepository, never()).saveAndFlush(any());
        verifyNoInteractions(statusHistoryRepository);
    }

    @Test
    @DisplayName("11. FAILED transition missing mandatory reason throws InvalidTransitionParametersException")
    void testFailedTransitionMissingReason() {
        Parcel parcel = createParcel(ParcelStatus.OUT_FOR_DELIVERY, 3L);
        when(parcelRepository.findById(parcelId)).thenReturn(Optional.of(parcel));
        when(courierAssignmentContract.hasActiveAssignment(parcelId, courierSub)).thenReturn(true);

        UpdateStatusRequest request = new UpdateStatusRequest(
                ParcelStatus.FAILED,
                3L,
                null,
                null, // Missing failure reason
                null
        );

        assertThatThrownBy(() -> serviceWithAssignment.updateStatus(
                parcelId,
                request,
                courierSub,
                List.of("ROLE_COURIER")
        )).isInstanceOf(InvalidTransitionParametersException.class)
                .hasMessageContaining("Failure reason is mandatory");

        verify(parcelRepository, never()).saveAndFlush(any());
        verifyNoInteractions(statusHistoryRepository);
    }

    @Test
    @DisplayName("12. Non-existent parcel throws ParcelNotFoundException")
    void testNonExistentParcelThrowsNotFound() {
        when(parcelRepository.findById(parcelId)).thenReturn(Optional.empty());

        UpdateStatusRequest request = new UpdateStatusRequest(
                ParcelStatus.PICKED_UP,
                1L,
                null,
                null,
                null
        );

        assertThatThrownBy(() -> serviceWithAssignment.updateStatus(
                parcelId,
                request,
                courierSub,
                List.of("ROLE_COURIER")
        )).isInstanceOf(ParcelNotFoundException.class)
                .hasMessageContaining(parcelId.toString());

        verify(parcelRepository, never()).saveAndFlush(any());
        verifyNoInteractions(statusHistoryRepository);
    }

    @Test
    @DisplayName("13. Dispatcher or Admin role is authorized without courier assignment check")
    void testDispatcherAuthorizedWithoutCourierAssignment() {
        Parcel parcel = createParcel(ParcelStatus.CREATED, 1L);
        when(parcelRepository.findById(parcelId)).thenReturn(Optional.of(parcel));
        when(parcelRepository.saveAndFlush(any(Parcel.class))).thenAnswer(invocation -> {
            Parcel p = invocation.getArgument(0);
            p.setVersion(2L);
            return p;
        });

        UpdateStatusRequest request = new UpdateStatusRequest(
                ParcelStatus.PICKED_UP,
                1L,
                "Hub dispatcher authorized pickup handover.",
                null,
                null
        );

        ParcelStatusChangeResponse response = serviceWithoutAssignment.updateStatus(
                parcelId,
                request,
                "dispatcher-user-01",
                List.of("ROLE_DISPATCHER")
        );

        assertThat(response.currentStatus()).isEqualTo("PICKED_UP");
        assertThat(response.stateVersion()).isEqualTo(2L);
        verify(statusHistoryRepository).save(any(StatusHistory.class));
    }

    @Test
    @DisplayName("14. Outbox event is created and saved when outboxEventRepository is provided")
    void testOutboxEventCreatedAndPersisted() {
        com.swiftdrop.deliveryservice.outbox.OutboxEventRepository outboxRepo = mock(com.swiftdrop.deliveryservice.outbox.OutboxEventRepository.class);
        com.swiftdrop.deliveryservice.outbox.OutboxEventFactory outboxFactory = new com.swiftdrop.deliveryservice.outbox.OutboxEventFactory();

        StatusTransitionService serviceWithOutbox = new StatusTransitionService(
                parcelRepository,
                statusHistoryRepository,
                courierAssignmentContract,
                outboxRepo,
                outboxFactory
        );

        Parcel parcel = createParcel(ParcelStatus.CREATED, 1L);
        when(parcelRepository.findById(parcelId)).thenReturn(Optional.of(parcel));
        when(parcelRepository.saveAndFlush(any(Parcel.class))).thenAnswer(invocation -> {
            Parcel p = invocation.getArgument(0);
            p.setVersion(2L);
            return p;
        });
        when(courierAssignmentContract.hasActiveAssignment(parcelId, courierSub)).thenReturn(true);

        UpdateStatusRequest request = new UpdateStatusRequest(
                ParcelStatus.PICKED_UP,
                1L,
                "Picked up by courier",
                null,
                null
        );

        serviceWithOutbox.updateStatus(
                parcelId,
                request,
                courierSub,
                List.of("ROLE_COURIER")
        );

        ArgumentCaptor<com.swiftdrop.deliveryservice.outbox.OutboxEvent> outboxCaptor =
                ArgumentCaptor.forClass(com.swiftdrop.deliveryservice.outbox.OutboxEvent.class);
        verify(outboxRepo).save(outboxCaptor.capture());

        com.swiftdrop.deliveryservice.outbox.OutboxEvent savedEvent = outboxCaptor.getValue();
        assertThat(savedEvent.getAggregateId()).isEqualTo(parcelId);
        assertThat(savedEvent.getAggregateVersion()).isEqualTo(2L);
        assertThat(savedEvent.getTopic()).isEqualTo("parcel.lifecycle.v1");
        assertThat(savedEvent.getPartitionKey()).isEqualTo(parcelId.toString());
        assertThat(savedEvent.getStatus()).isEqualTo(com.swiftdrop.deliveryservice.outbox.OutboxStatus.PENDING);
    }
}
