package com.swiftdrop.deliveryservice.assignment;

import com.swiftdrop.deliveryservice.assignment.dto.AssignCourierRequest;
import com.swiftdrop.deliveryservice.assignment.dto.AssignmentAuditResponse;
import com.swiftdrop.deliveryservice.assignment.dto.AssignmentResponse;
import com.swiftdrop.deliveryservice.assignment.dto.CloseAssignmentRequest;
import com.swiftdrop.deliveryservice.assignment.dto.ReassignCourierRequest;
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

    @Mock
    private DeliveryAssignmentAuditRepository assignmentAuditRepository;

    private DeliveryAssignmentService service;

    private final UUID parcelId = UUID.randomUUID();
    private final String courierSub = "courier-sub-101";
    private final String replacementCourierSub = "courier-sub-202";
    private final String dispatcherSub = "dispatcher-sub-007";
    private final String trackingNumber = "SWD-20261025-ASG1";

    @BeforeEach
    void setUp() {
        service = new DeliveryAssignmentService(
                assignmentRepository,
                parcelRepository,
                courierEligibilityClient,
                assignmentAuditRepository
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

        assertThat(parcel.getVersion()).isEqualTo(2L);
        assertThat(parcel.getStatus()).isEqualTo(ParcelStatus.CREATED);

        ArgumentCaptor<DeliveryAssignment> captor = ArgumentCaptor.forClass(DeliveryAssignment.class);
        verify(assignmentRepository).save(captor.capture());
        DeliveryAssignment saved = captor.getValue();
        assertThat(saved.getParcelId()).isEqualTo(parcelId);
        assertThat(saved.getCourierSub()).isEqualTo(courierSub);
        assertThat(saved.getAssignedBy()).isEqualTo(dispatcherSub);
        assertThat(saved.isActive()).isTrue();

        // Verify audit saved
        verify(assignmentAuditRepository).save(any(DeliveryAssignmentAudit.class));
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
        Parcel parcel = createParcel(ParcelStatus.CREATED, 2L);
        when(parcelRepository.findById(parcelId)).thenReturn(Optional.of(parcel));

        AssignCourierRequest request = new AssignCourierRequest(parcelId, courierSub, 1L);

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

    // --- Reassignment Tests (Issue #125) ---

    @Test
    @DisplayName("9. Dispatcher successfully reassigns eligible courier to CREATED parcel")
    void testSuccessfulReassignment() {
        UUID currentAssignmentId = UUID.randomUUID();
        DeliveryAssignment currentAssignment = new DeliveryAssignment(
                currentAssignmentId, parcelId, courierSub, dispatcherSub, Instant.now().minusSeconds(100), null, true
        );

        Parcel parcel = createParcel(ParcelStatus.CREATED, 2L);
        when(parcelRepository.findById(parcelId)).thenReturn(Optional.of(parcel));
        when(assignmentRepository.findByParcelIdAndActiveTrue(parcelId)).thenReturn(Optional.of(currentAssignment));
        when(courierEligibilityClient.checkEligibility(replacementCourierSub))
                .thenReturn(new CourierEligibilityResult(replacementCourierSub, true, true, "ELIGIBLE"));
        when(parcelRepository.saveAndFlush(any(Parcel.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ReassignCourierRequest request = new ReassignCourierRequest(
                parcelId,
                replacementCourierSub,
                2L,
                currentAssignmentId,
                "COURIER_VEHICLE_BREAKDOWN"
        );

        AssignmentResponse response = service.reassignCourier(
                request,
                dispatcherSub,
                List.of("ROLE_DISPATCHER")
        );

        assertThat(response.parcelId()).isEqualTo(parcelId);
        assertThat(response.courierSub()).isEqualTo(replacementCourierSub);
        assertThat(response.active()).isTrue();
        assertThat(response.assignedBy()).isEqualTo(dispatcherSub);
        assertThat(response.newParcelVersion()).isEqualTo(3L);
        assertThat(response.reason()).isEqualTo("COURIER_VEHICLE_BREAKDOWN");

        // Old assignment deactivated
        assertThat(currentAssignment.isActive()).isFalse();
        assertThat(currentAssignment.getEndedAt()).isNotNull();

        // Parcel version bumped
        assertThat(parcel.getVersion()).isEqualTo(3L);

        // Audit entry recorded
        ArgumentCaptor<DeliveryAssignmentAudit> auditCaptor = ArgumentCaptor.forClass(DeliveryAssignmentAudit.class);
        verify(assignmentAuditRepository).save(auditCaptor.capture());
        DeliveryAssignmentAudit audit = auditCaptor.getValue();
        assertThat(audit.getAction()).isEqualTo("REASSIGNED");
        assertThat(audit.getCourierSub()).isEqualTo(replacementCourierSub);
        assertThat(audit.getPreviousCourierSub()).isEqualTo(courierSub);
        assertThat(audit.getPreviousAssignmentId()).isEqualTo(currentAssignmentId);
        assertThat(audit.getReason()).isEqualTo("COURIER_VEHICLE_BREAKDOWN");
        assertThat(audit.getParcelVersion()).isEqualTo(3L);
    }

    @Test
    @DisplayName("10. Reassignment rejected if parcel not in CREATED state")
    void testReassignNonCreatedParcelRejected() {
        Parcel parcel = createParcel(ParcelStatus.PICKED_UP, 2L);
        when(parcelRepository.findById(parcelId)).thenReturn(Optional.of(parcel));

        ReassignCourierRequest request = new ReassignCourierRequest(
                parcelId,
                replacementCourierSub,
                2L,
                null,
                "BREAKDOWN"
        );

        assertThatThrownBy(() -> service.reassignCourier(
                request,
                dispatcherSub,
                List.of("ROLE_DISPATCHER")
        )).isInstanceOf(InvalidParcelStateException.class)
                .hasMessageContaining("Reassignment is only permitted while parcel is in CREATED state");

        verifyNoInteractions(courierEligibilityClient);
    }

    @Test
    @DisplayName("11. Reassignment rejected if expectedVersion does not match")
    void testReassignVersionMismatch() {
        Parcel parcel = createParcel(ParcelStatus.CREATED, 3L);
        when(parcelRepository.findById(parcelId)).thenReturn(Optional.of(parcel));

        ReassignCourierRequest request = new ReassignCourierRequest(
                parcelId,
                replacementCourierSub,
                2L, // stale
                null,
                "BREAKDOWN"
        );

        assertThatThrownBy(() -> service.reassignCourier(
                request,
                dispatcherSub,
                List.of("ROLE_DISPATCHER")
        )).isInstanceOf(AssignmentConflictException.class)
                .hasMessageContaining("expected parcel version 2 but was 3");

        verifyNoInteractions(courierEligibilityClient);
    }

    @Test
    @DisplayName("12. Reassignment rejected if no active assignment exists")
    void testReassignNoActiveAssignment() {
        Parcel parcel = createParcel(ParcelStatus.CREATED, 1L);
        when(parcelRepository.findById(parcelId)).thenReturn(Optional.of(parcel));
        when(assignmentRepository.findByParcelIdAndActiveTrue(parcelId)).thenReturn(Optional.empty());

        ReassignCourierRequest request = new ReassignCourierRequest(
                parcelId,
                replacementCourierSub,
                1L,
                null,
                "BREAKDOWN"
        );

        assertThatThrownBy(() -> service.reassignCourier(
                request,
                dispatcherSub,
                List.of("ROLE_DISPATCHER")
        )).isInstanceOf(AssignmentConflictException.class)
                .hasMessageContaining("does not have an active courier assignment to reassign");
    }

    @Test
    @DisplayName("13. Reassignment rejected if currentAssignmentId mismatch")
    void testReassignCurrentAssignmentIdMismatch() {
        UUID actualAssignmentId = UUID.randomUUID();
        UUID expectedMismatchId = UUID.randomUUID();

        DeliveryAssignment currentAssignment = new DeliveryAssignment(
                actualAssignmentId, parcelId, courierSub, dispatcherSub, Instant.now(), null, true
        );

        Parcel parcel = createParcel(ParcelStatus.CREATED, 2L);
        when(parcelRepository.findById(parcelId)).thenReturn(Optional.of(parcel));
        when(assignmentRepository.findByParcelIdAndActiveTrue(parcelId)).thenReturn(Optional.of(currentAssignment));

        ReassignCourierRequest request = new ReassignCourierRequest(
                parcelId,
                replacementCourierSub,
                2L,
                expectedMismatchId,
                "BREAKDOWN"
        );

        assertThatThrownBy(() -> service.reassignCourier(
                request,
                dispatcherSub,
                List.of("ROLE_DISPATCHER")
        )).isInstanceOf(AssignmentConflictException.class)
                .hasMessageContaining("does not match active assignment");
    }

    @Test
    @DisplayName("14. Reassignment rejected if replacement courier is same as current courier")
    void testReassignSameCourierRejected() {
        DeliveryAssignment currentAssignment = new DeliveryAssignment(
                UUID.randomUUID(), parcelId, courierSub, dispatcherSub, Instant.now(), null, true
        );

        Parcel parcel = createParcel(ParcelStatus.CREATED, 2L);
        when(parcelRepository.findById(parcelId)).thenReturn(Optional.of(parcel));
        when(assignmentRepository.findByParcelIdAndActiveTrue(parcelId)).thenReturn(Optional.of(currentAssignment));

        ReassignCourierRequest request = new ReassignCourierRequest(
                parcelId,
                courierSub, // same courier
                2L,
                null,
                "BREAKDOWN"
        );

        assertThatThrownBy(() -> service.reassignCourier(
                request,
                dispatcherSub,
                List.of("ROLE_DISPATCHER")
        )).isInstanceOf(AssignmentConflictException.class)
                .hasMessageContaining("already actively assigned to parcel");
    }

    @Test
    @DisplayName("15. Reassignment rejected if replacement courier is ineligible")
    void testReassignIneligibleCourierRejected() {
        DeliveryAssignment currentAssignment = new DeliveryAssignment(
                UUID.randomUUID(), parcelId, courierSub, dispatcherSub, Instant.now(), null, true
        );

        Parcel parcel = createParcel(ParcelStatus.CREATED, 2L);
        when(parcelRepository.findById(parcelId)).thenReturn(Optional.of(parcel));
        when(assignmentRepository.findByParcelIdAndActiveTrue(parcelId)).thenReturn(Optional.of(currentAssignment));
        when(courierEligibilityClient.checkEligibility(replacementCourierSub))
                .thenReturn(new CourierEligibilityResult(replacementCourierSub, false, false, "ACCOUNT_SUSPENDED"));

        ReassignCourierRequest request = new ReassignCourierRequest(
                parcelId,
                replacementCourierSub,
                2L,
                null,
                "BREAKDOWN"
        );

        assertThatThrownBy(() -> service.reassignCourier(
                request,
                dispatcherSub,
                List.of("ROLE_DISPATCHER")
        )).isInstanceOf(IneligibleCourierException.class)
                .hasMessageContaining("ACCOUNT_SUSPENDED");

        // Old assignment remains active!
        assertThat(currentAssignment.isActive()).isTrue();
    }

    // --- Closure Tests (Issue #125) ---

    @Test
    @DisplayName("16. Dispatcher successfully closes active assignment on CREATED parcel")
    void testSuccessfulClosure() {
        UUID currentAssignmentId = UUID.randomUUID();
        DeliveryAssignment currentAssignment = new DeliveryAssignment(
                currentAssignmentId, parcelId, courierSub, dispatcherSub, Instant.now().minusSeconds(50), null, true
        );

        Parcel parcel = createParcel(ParcelStatus.CREATED, 2L);
        when(parcelRepository.findById(parcelId)).thenReturn(Optional.of(parcel));
        when(assignmentRepository.findByParcelIdAndActiveTrue(parcelId)).thenReturn(Optional.of(currentAssignment));
        when(parcelRepository.saveAndFlush(any(Parcel.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CloseAssignmentRequest request = new CloseAssignmentRequest(
                parcelId,
                2L,
                currentAssignmentId,
                "MANUAL_DISPATCHER_UNASSIGNMENT"
        );

        AssignmentResponse response = service.closeAssignment(
                request,
                dispatcherSub,
                List.of("ROLE_ADMIN")
        );

        assertThat(response.parcelId()).isEqualTo(parcelId);
        assertThat(response.active()).isFalse();
        assertThat(response.endedAt()).isNotNull();
        assertThat(response.newParcelVersion()).isEqualTo(3L);

        assertThat(currentAssignment.isActive()).isFalse();
        assertThat(currentAssignment.getEndedAt()).isNotNull();

        ArgumentCaptor<DeliveryAssignmentAudit> auditCaptor = ArgumentCaptor.forClass(DeliveryAssignmentAudit.class);
        verify(assignmentAuditRepository).save(auditCaptor.capture());
        DeliveryAssignmentAudit audit = auditCaptor.getValue();
        assertThat(audit.getAction()).isEqualTo("CLOSED");
        assertThat(audit.getActorRole()).isEqualTo("ADMIN");
        assertThat(audit.getParcelVersion()).isEqualTo(3L);
    }

    @Test
    @DisplayName("17. Assignment closure rejected if parcel status is not CREATED")
    void testCloseNonCreatedParcelRejected() {
        Parcel parcel = createParcel(ParcelStatus.DELIVERED, 5L);
        when(parcelRepository.findById(parcelId)).thenReturn(Optional.of(parcel));

        CloseAssignmentRequest request = new CloseAssignmentRequest(
                parcelId,
                5L,
                null,
                "CANCEL"
        );

        assertThatThrownBy(() -> service.closeAssignment(
                request,
                dispatcherSub,
                List.of("ROLE_DISPATCHER")
        )).isInstanceOf(InvalidParcelStateException.class)
                .hasMessageContaining("Assignment closure is only permitted while parcel is in CREATED state");
    }

    @Test
    @DisplayName("18. Terminal status transition automatically and atomically closes active assignment")
    void testTerminalStateAssignmentClosure() {
        UUID currentAssignmentId = UUID.randomUUID();
        DeliveryAssignment currentAssignment = new DeliveryAssignment(
                currentAssignmentId, parcelId, courierSub, dispatcherSub, Instant.now().minusSeconds(500), null, true
        );

        Parcel parcel = createParcel(ParcelStatus.DELIVERED, 4L);
        when(assignmentRepository.findByParcelIdAndActiveTrue(parcelId)).thenReturn(Optional.of(currentAssignment));
        when(parcelRepository.findById(parcelId)).thenReturn(Optional.of(parcel));

        Instant endedAt = Instant.now();
        service.closeActiveAssignmentForTerminalParcel(parcelId, endedAt);

        assertThat(currentAssignment.isActive()).isFalse();
        assertThat(currentAssignment.getEndedAt()).isEqualTo(endedAt);
        verify(assignmentRepository).save(currentAssignment);

        ArgumentCaptor<DeliveryAssignmentAudit> auditCaptor = ArgumentCaptor.forClass(DeliveryAssignmentAudit.class);
        verify(assignmentAuditRepository).save(auditCaptor.capture());
        assertThat(auditCaptor.getValue().getAction()).isEqualTo("TERMINATED");
        assertThat(auditCaptor.getValue().getParcelVersion()).isEqualTo(4L);
    }

    // --- Audit Query Tests (Issue #125) ---

    @Test
    @DisplayName("19. Dispatcher retrieves assignment audit history")
    void testGetAssignmentAuditHistory() {
        when(parcelRepository.existsById(parcelId)).thenReturn(true);
        UUID auditId = UUID.randomUUID();
        DeliveryAssignmentAudit audit = new DeliveryAssignmentAudit(
                auditId,
                parcelId,
                UUID.randomUUID(),
                courierSub,
                "ASSIGNED",
                dispatcherSub,
                "DISPATCHER",
                null,
                null,
                "Initial",
                1L,
                Instant.now()
        );
        when(assignmentAuditRepository.findByParcelIdOrderByOccurredAtDesc(parcelId))
                .thenReturn(List.of(audit));

        List<AssignmentAuditResponse> history = service.getAssignmentAuditHistory(
                parcelId,
                dispatcherSub,
                List.of("ROLE_DISPATCHER")
        );

        assertThat(history).hasSize(1);
        assertThat(history.get(0).auditId()).isEqualTo(auditId);
        assertThat(history.get(0).action()).isEqualTo("ASSIGNED");
    }

    @Test
    @DisplayName("20. Non-dispatcher role denied access to assignment audit history")
    void testAuditHistoryAccessDenied() {
        assertThatThrownBy(() -> service.getAssignmentAuditHistory(
                parcelId,
                courierSub,
                List.of("ROLE_COURIER")
        )).isInstanceOf(AssignmentAccessDeniedException.class)
                .hasMessageContaining("DISPATCHER or ADMIN role required");

        verifyNoInteractions(parcelRepository);
        verifyNoInteractions(assignmentAuditRepository);
    }
}
