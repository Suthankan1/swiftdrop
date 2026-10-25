package com.swiftdrop.deliveryservice.status;

import com.swiftdrop.deliveryservice.assignment.CourierAssignmentContract;
import com.swiftdrop.deliveryservice.core.Parcel;
import com.swiftdrop.deliveryservice.core.ParcelNotFoundException;
import com.swiftdrop.deliveryservice.core.ParcelRepository;
import com.swiftdrop.deliveryservice.core.ParcelStatus;
import com.swiftdrop.deliveryservice.status.dto.ParcelStatusChangeResponse;
import com.swiftdrop.deliveryservice.status.dto.UpdateStatusRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.Objects;
import java.util.UUID;

/**
 * Domain Service for atomically executing guarded parcel status transitions and appending immutable audit history.
 *
 * Enforces the approved lifecycle transition contract, optimistic locking (expectedVersion),
 * and actor authorization guards (including the Issue #24 assignment dependency gate).
 */
@Service
public class StatusTransitionService {

    private final ParcelRepository parcelRepository;
    private final StatusHistoryRepository statusHistoryRepository;
    private final CourierAssignmentContract courierAssignmentContract;

    @Autowired
    public StatusTransitionService(
            ParcelRepository parcelRepository,
            StatusHistoryRepository statusHistoryRepository,
            @Autowired(required = false) CourierAssignmentContract courierAssignmentContract) {
        this.parcelRepository = parcelRepository;
        this.statusHistoryRepository = statusHistoryRepository;
        this.courierAssignmentContract = courierAssignmentContract;
    }

    /**
     * Atomically executes a lifecycle state transition and appends an audit history record.
     *
     * @param parcelId ID of the parcel to transition
     * @param request status update command containing newStatus, expectedVersion, and optional reason/proof
     * @param actorSub verified Keycloak JWT subject identifier
     * @param roles authorities/roles held by the caller
     * @return ParcelStatusChangeResponse details of the committed transition
     */
    @Transactional
    public ParcelStatusChangeResponse updateStatus(
            UUID parcelId,
            UpdateStatusRequest request,
            String actorSub,
            Collection<String> roles) {

        Objects.requireNonNull(parcelId, "parcelId cannot be null");
        Objects.requireNonNull(request, "request cannot be null");
        Objects.requireNonNull(actorSub, "actorSub cannot be null");

        // 1. Retrieve parcel aggregate root
        Parcel parcel = parcelRepository.findById(parcelId)
                .orElseThrow(() -> new ParcelNotFoundException(parcelId));

        // 2. Validate actor authorization against application roles and active courier assignment
        authorizeActor(parcelId, actorSub, roles);

        // 3. Optimistic locking verification: incoming expectedVersion must match current aggregate version
        if (!Objects.equals(request.expectedVersion(), parcel.getVersion())) {
            throw new VersionConflictException(request.expectedVersion(), parcel.getVersion());
        }

        // 4. Validate transition admissibility against authoritative contract
        ParcelStatus previousStatus = parcel.getStatus();
        ParcelLifecycleTransitionContract.validateTransition(
                previousStatus,
                request.newStatus(),
                request.reason(),
                request.proofId()
        );

        // 5. Update parcel status; optimistic locking increments aggregate version upon flush
        parcel.setStatus(request.newStatus());
        Parcel updatedParcel = parcelRepository.saveAndFlush(parcel);
        Long resultingVersion = updatedParcel.getVersion();

        // 6. Record append-only immutable history entry in same local database transaction
        Instant occurredAt = Instant.now();
        StatusHistory history = new StatusHistory(
                UUID.randomUUID(),
                parcelId,
                previousStatus,
                request.newStatus(),
                request.reason(),
                request.note(),
                request.proofId(),
                actorSub,
                resultingVersion,
                occurredAt
        );
        statusHistoryRepository.save(history);

        return new ParcelStatusChangeResponse(
                parcel.getParcelId(),
                parcel.getTrackingNumber(),
                previousStatus != null ? previousStatus.name() : null,
                parcel.getStatus().name(),
                resultingVersion,
                resultingVersion,
                occurredAt
        );
    }

    private void authorizeActor(UUID parcelId, String actorSub, Collection<String> roles) {
        if (roles == null || roles.isEmpty()) {
            throw new StatusAccessDeniedException("Forbidden - missing application roles.");
        }

        boolean isCourier = roles.contains("ROLE_COURIER") || roles.contains("COURIER");
        boolean isDispatcher = roles.contains("ROLE_DISPATCHER") || roles.contains("DISPATCHER");
        boolean isAdmin = roles.contains("ROLE_ADMIN") || roles.contains("ADMIN");

        if (!isCourier && !isDispatcher && !isAdmin) {
            throw new StatusAccessDeniedException("Forbidden - caller lacks authorized role to transition parcel status.");
        }

        if (isCourier) {
            // Assignment Dependency Gate:
            // Issue #24 is open. We MUST verify courierAssignmentContract.
            if (courierAssignmentContract == null) {
                throw new StatusAccessDeniedException(
                        "Courier assignment provider unavailable. Courier status transition is blocked pending Issue #24."
                );
            }
            if (!courierAssignmentContract.hasActiveAssignment(parcelId, actorSub)) {
                throw new StatusAccessDeniedException(
                        "Forbidden - courier " + actorSub + " is not actively assigned to parcel " + parcelId
                );
            }
        }
    }
}
