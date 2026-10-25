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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Domain Service managing courier assignments for Delivery Operations.
 *
 * Implements the shared CourierAssignmentContract expected by status/history transitions
 * and executes the single-active-courier assignment command with concurrency guards.
 */
@Service
public class DeliveryAssignmentService implements CourierAssignmentContract {

    private final DeliveryAssignmentRepository assignmentRepository;
    private final ParcelRepository parcelRepository;
    private final CourierEligibilityClient courierEligibilityClient;

    @Autowired
    public DeliveryAssignmentService(
            DeliveryAssignmentRepository assignmentRepository,
            ParcelRepository parcelRepository,
            CourierEligibilityClient courierEligibilityClient) {
        this.assignmentRepository = assignmentRepository;
        this.parcelRepository = parcelRepository;
        this.courierEligibilityClient = courierEligibilityClient;
    }

    // --- CourierAssignmentContract Implementation (Production Bean) ---

    @Override
    public boolean hasActiveAssignment(UUID parcelId, String courierSub) {
        if (parcelId == null || courierSub == null || courierSub.isBlank()) {
            return false;
        }
        return assignmentRepository.existsByParcelIdAndCourierSubAndActiveTrue(parcelId, courierSub.trim());
    }

    @Override
    public Optional<String> getActiveCourierSub(UUID parcelId) {
        if (parcelId == null) {
            return Optional.empty();
        }
        return assignmentRepository.findByParcelIdAndActiveTrue(parcelId)
                .map(DeliveryAssignment::getCourierSub);
    }

    @Override
    public int getActiveCourierWorkload(String courierSub) {
        if (courierSub == null || courierSub.isBlank()) {
            return 0;
        }
        return assignmentRepository.countByCourierSubAndActiveTrue(courierSub.trim());
    }

    // --- Assignment Command Implementation ---

    /**
     * Dispatches an unassigned parcel in CREATED state to an eligible courier.
     * Enforces optimistic locking, single-active assignment invariant, and eligibility checks.
     */
    @Transactional
    public AssignmentResponse assignCourier(
            AssignCourierRequest request,
            String dispatcherSub,
            Collection<String> roles) {

        Objects.requireNonNull(request, "request cannot be null");
        Objects.requireNonNull(dispatcherSub, "dispatcherSub cannot be null");

        // 1. Authorize actor: DISPATCHER or ADMIN role required
        authorizeDispatcher(roles);

        // 2. Retrieve parcel aggregate root
        Parcel parcel = parcelRepository.findById(request.parcelId())
                .orElseThrow(() -> new ParcelNotFoundException(request.parcelId()));

        // 3. State guard (DEC-ASG-02): Only parcels in CREATED state can be assigned
        if (parcel.getStatus() != ParcelStatus.CREATED) {
            throw new InvalidParcelStateException(
                    "Parcel " + parcel.getParcelId() + " is in status " + parcel.getStatus() +
                            " and cannot be assigned. Only parcels in CREATED state are eligible for assignment."
            );
        }

        // 4. Concurrency check: expectedVersion must match parcel.version
        if (!Objects.equals(request.expectedVersion(), parcel.getVersion())) {
            throw new AssignmentConflictException(
                    "Optimistic lock failure: expected parcel version " + request.expectedVersion() +
                            " but was " + parcel.getVersion()
            );
        }

        // 5. Single active assignment guard (DEC-ASG-03)
        if (assignmentRepository.existsByParcelIdAndActiveTrue(request.parcelId())) {
            throw new AssignmentConflictException(
                    "Parcel " + request.parcelId() + " already has an active courier assignment."
            );
        }

        // 6. Courier business eligibility verification via Accounts Service (DEC-ASG-01 / IF-GRPC-01)
        CourierEligibilityResult eligibility = courierEligibilityClient.checkEligibility(request.courierSub());
        if (!eligibility.isApproved()) {
            throw new IneligibleCourierException(
                    "Courier " + request.courierSub() + " is not eligible for assignment: " + eligibility.rejectionReason()
            );
        }

        // 7. Atomically increment parcel version and save assignment
        parcel.setVersion(parcel.getVersion() + 1);
        Parcel updatedParcel = parcelRepository.saveAndFlush(parcel);
        Long newParcelVersion = updatedParcel.getVersion();

        UUID assignmentId = UUID.randomUUID();
        Instant now = Instant.now();
        DeliveryAssignment assignment = new DeliveryAssignment(
                assignmentId,
                parcel.getParcelId(),
                request.courierSub().trim(),
                dispatcherSub,
                now,
                null,
                true
        );
        assignmentRepository.save(assignment);

        return new AssignmentResponse(
                assignmentId,
                parcel.getParcelId(),
                request.courierSub().trim(),
                true,
                now,
                dispatcherSub,
                newParcelVersion
        );
    }

    private void authorizeDispatcher(Collection<String> roles) {
        if (roles == null || roles.isEmpty()) {
            throw new AssignmentAccessDeniedException("Forbidden - missing application roles.");
        }

        boolean isDispatcher = roles.contains("ROLE_DISPATCHER") || roles.contains("DISPATCHER");
        boolean isAdmin = roles.contains("ROLE_ADMIN") || roles.contains("ADMIN");

        if (!isDispatcher && !isAdmin) {
            throw new AssignmentAccessDeniedException("Forbidden - DISPATCHER or ADMIN role required for courier assignment.");
        }
    }
}
