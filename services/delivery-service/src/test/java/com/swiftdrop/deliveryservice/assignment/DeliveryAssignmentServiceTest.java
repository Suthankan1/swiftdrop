package com.swiftdrop.deliveryservice.assignment;

import com.swiftdrop.deliveryservice.assignment.dto.AssignCourierRequest;
import com.swiftdrop.deliveryservice.assignment.dto.AssignmentResponse;
import com.swiftdrop.deliveryservice.assignment.exception.AssignmentAccessDeniedException;
import com.swiftdrop.deliveryservice.assignment.exception.AssignmentConflictException;
import com.swiftdrop.deliveryservice.assignment.exception.IneligibleCourierException;
import com.swiftdrop.deliveryservice.assignment.exception.InvalidParcelStateException;
import com.swiftdrop.deliveryservice.core.Parcel;
import com.swiftdrop.deliveryservice.core.ParcelNotFoundException;
import com.swiftdrop.deliveryservice.core.ParcelRepository;
import com.swiftdrop.deliveryservice.core.ParcelStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeliveryAssignmentServiceTest {

    @Mock
    private DeliveryAssignmentRepository assignmentRepository;

    @Mock
    private ParcelRepository parcelRepository;

    @Mock
    private CourierEligibilityClient courierEligibilityClient;

    private DeliveryAssignmentService service;

    private final UUID parcelId = UUID.randomUUID();
    private final String courierSub = "courier-sub-101";
    private final String dispatcherSub = "dispatcher-sub-007";
    private final String trackingNumber = "SWD-20261025-ASG1";

    @BeforeEach
    void setUp() {
        service = new DeliveryAssignmentService(
                assignmentRepository,
                parcelRepository,
                courierEligibilityClient
        );
    }

    private Parcel createParcel(ParcelStatus status, Long version) {
        Parcel parcel = new Parcel(
                parcelId,
                trackingNumber,
                "sender-customer-1",
                UUID.randomUUID(),
                UUID.randomUUID(),
                "2026.1",
                120000L,
                "LKR",
                new BigDecimal("1.500")
        );
        parcel.setStatus(status);
        parcel.setVersion(version);
        return parcel;
    }

    @Test
    @DisplayName("1. Dispatcher successfully assigns eligible courier to CREATED parcel")
    void testSuccessfulAssignment() {
        Parcel parcel = createParcel(ParcelStatus.CREATED, 1L);
        when(parcelRepository.findById(parcelId)).thenReturn(Optional.of(parcel));
        when(assignmentRepository.existsByParcelIdAndActiveTrue(parcelId)).thenReturn(false);
        when(courierEligibilityClient.checkEligibility(courierSub))
                .thenReturn(new CourierEligibilityResult(courierSub, true, true, "ELIGIBLE"));
        when(parcelRepository.saveAndFlush(any(Parcel.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AssignCourierRequest request = new AssignCourierRequest(parcelId, courierSub, 1L);

        AssignmentResponse response = service.assignCourier(
                request,
                dispatcherSub,
                List.of("ROLE_DISPATCHER")
        );

        assertThat(response.parcelId()).isEqualTo(parcelId);
        assertThat(response.courierSub()).isEqualTo(courierSub);
        assertThat(response.active()).isTrue();
        assertThat(response.assignedBy()).isEqualTo(dispatcherSub);
        assertThat(response.newParcelVersion()).isEqualTo(2L);
        assertThat(response.assignedAt()).isNotNull();

        // Verify parcel version incremented while status remains CREATED (DEC-ASG-04)
        assertThat(parcel.getVersion()).isEqualTo(2L);
        assertThat(parcel.getStatus()).isEqualTo(ParcelStatus.CREATED);

        // Verify DeliveryAssignment persisted
        ArgumentCaptor<DeliveryAssignment> captor = ArgumentCaptor.forClass(DeliveryAssignment.class);
        verify(assignmentRepository).save(captor.capture());
        DeliveryAssignment saved = captor.getValue();
        assertThat(saved.getParcelId()).isEqualTo(parcelId);
        assertThat(saved.getCourierSub()).isEqualTo(courierSub);
        assertThat(saved.getAssignedBy()).isEqualTo(dispatcherSub);
        assertThat(saved.isActive()).isTrue();
    }

    @Test
    @DisplayName("2. Non-dispatcher role (e.g. COURIER) is rejected with AssignmentAccessDeniedException")
    void testUnauthorizedRoleDenied() {
        AssignCourierRequest request = new AssignCourierRequest(parcelId, courierSub, 1L);

        assertThatThrownBy(() -> service.assignCourier(
                request,
                dispatcherSub,
                List.of("ROLE_COURIER")
        )).isInstanceOf(AssignmentAccessDeniedException.class)
                .hasMessageContaining("DISPATCHER or ADMIN role required");

        verifyNoInteractions(parcelRepository);
        verifyNoInteractions(assignmentRepository);
    }

    @Test
    @DisplayName("3. Ineligible courier is rejected with IneligibleCourierException")
    void testIneligibleCourierRejected() {
        Parcel parcel = createParcel(ParcelStatus.CREATED, 1L);
        when(parcelRepository.findById(parcelId)).thenReturn(Optional.of(parcel));
        when(assignmentRepository.existsByParcelIdAndActiveTrue(parcelId)).thenReturn(false);
        when(courierEligibilityClient.checkEligibility(courierSub))
                .thenReturn(new CourierEligibilityResult(courierSub, false, false, "COURIER_SUSPENDED"));

        AssignCourierRequest request = new AssignCourierRequest(parcelId, courierSub, 1L);

        assertThatThrownBy(() -> service.assignCourier(
                request,
                dispatcherSub,
                List.of("ROLE_DISPATCHER")
        )).isInstanceOf(IneligibleCourierException.class)
                .hasMessageContaining("COURIER_SUSPENDED");

        verify(assignmentRepository, never()).save(any());
    }

    @Test
    @DisplayName("4. Parcel not in CREATED state throws InvalidParcelStateException")
    void testAssignNonCreatedParcelRejected() {
        Parcel parcel = createParcel(ParcelStatus.IN_TRANSIT, 2L);
        when(parcelRepository.findById(parcelId)).thenReturn(Optional.of(parcel));

        AssignCourierRequest request = new AssignCourierRequest(parcelId, courierSub, 2L);

        assertThatThrownBy(() -> service.assignCourier(
                request,
                dispatcherSub,
                List.of("ROLE_DISPATCHER")
        )).isInstanceOf(InvalidParcelStateException.class)
                .hasMessageContaining("Only parcels in CREATED state are eligible for assignment");

        verifyNoInteractions(courierEligibilityClient);
        verify(assignmentRepository, never()).save(any());
    }

    @Test
    @DisplayName("5. Mismatched expectedVersion throws AssignmentConflictException")
    void testOptimisticVersionConflict() {
        Parcel parcel = createParcel(ParcelStatus.CREATED, 2L); // actual is 2
        when(parcelRepository.findById(parcelId)).thenReturn(Optional.of(parcel));

        AssignCourierRequest request = new AssignCourierRequest(parcelId, courierSub, 1L); // expected is 1

        assertThatThrownBy(() -> service.assignCourier(
                request,
                dispatcherSub,
                List.of("ROLE_DISPATCHER")
        )).isInstanceOf(AssignmentConflictException.class)
                .hasMessageContaining("expected parcel version 1 but was 2");

        verifyNoInteractions(courierEligibilityClient);
        verify(assignmentRepository, never()).save(any());
    }

    @Test
    @DisplayName("6. Parcel with existing active assignment throws AssignmentConflictException")
    void testAlreadyAssignedConflict() {
        Parcel parcel = createParcel(ParcelStatus.CREATED, 1L);
        when(parcelRepository.findById(parcelId)).thenReturn(Optional.of(parcel));
        when(assignmentRepository.existsByParcelIdAndActiveTrue(parcelId)).thenReturn(true);

        AssignCourierRequest request = new AssignCourierRequest(parcelId, courierSub, 1L);

        assertThatThrownBy(() -> service.assignCourier(
                request,
                dispatcherSub,
                List.of("ROLE_DISPATCHER")
        )).isInstanceOf(AssignmentConflictException.class)
                .hasMessageContaining("already has an active courier assignment");

        verifyNoInteractions(courierEligibilityClient);
        verify(assignmentRepository, never()).save(any());
    }

    @Test
    @DisplayName("7. Non-existent parcel throws ParcelNotFoundException")
    void testParcelNotFound() {
        when(parcelRepository.findById(parcelId)).thenReturn(Optional.empty());

        AssignCourierRequest request = new AssignCourierRequest(parcelId, courierSub, 1L);

        assertThatThrownBy(() -> service.assignCourier(
                request,
                dispatcherSub,
                List.of("ROLE_DISPATCHER")
        )).isInstanceOf(ParcelNotFoundException.class)
                .hasMessageContaining(parcelId.toString());

        verifyNoInteractions(courierEligibilityClient);
        verify(assignmentRepository, never()).save(any());
    }

    @Test
    @DisplayName("8. CourierAssignmentContract query methods return accurate state")
    void testContractQueries() {
        when(assignmentRepository.existsByParcelIdAndCourierSubAndActiveTrue(parcelId, courierSub)).thenReturn(true);
        assertThat(service.hasActiveAssignment(parcelId, courierSub)).isTrue();
        assertThat(service.hasActiveAssignment(parcelId, "other-courier")).isFalse();

        DeliveryAssignment activeAsg = new DeliveryAssignment(
                UUID.randomUUID(), parcelId, courierSub, dispatcherSub, Instant.now(), null, true
        );
        when(assignmentRepository.findByParcelIdAndActiveTrue(parcelId)).thenReturn(Optional.of(activeAsg));
        assertThat(service.getActiveCourierSub(parcelId)).contains(courierSub);

        when(assignmentRepository.countByCourierSubAndActiveTrue(courierSub)).thenReturn(3);
        assertThat(service.getActiveCourierWorkload(courierSub)).isEqualTo(3);
    }
}
