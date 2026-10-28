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
import com.swiftdrop.deliveryservice.query.dto.PagedResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Domain Service managing courier assignments, reassignments, closures, and audit lifecycle for Delivery Operations.
 *
 * Implements the shared CourierAssignmentContract and enforces single-active-courier assignment invariants,
 * optimistic aggregate version locking, Accounts courier eligibility verification, and append-only audit persistence.
 */
@Service
public class DeliveryAssignmentService implements CourierAssignmentContract {

    private final DeliveryAssignmentRepository assignmentRepository;
    private final ParcelRepository parcelRepository;
    private final CourierEligibilityClient courierEligibilityClient;
    private final DeliveryAssignmentAuditRepository assignmentAuditRepository;

    public DeliveryAssignmentService(
            DeliveryAssignmentRepository assignmentRepository,
            ParcelRepository parcelRepository,
            CourierEligibilityClient courierEligibilityClient) {
        this(assignmentRepository, parcelRepository, courierEligibilityClient, null);
    }

    @Autowired
    public DeliveryAssignmentService(
            DeliveryAssignmentRepository assignmentRepository,
            ParcelRepository parcelRepository,
            CourierEligibilityClient courierEligibilityClient,
            @Autowired(required = false) DeliveryAssignmentAuditRepository assignmentAuditRepository) {
        this.assignmentRepository = assignmentRepository;
        this.parcelRepository = parcelRepository;
        this.courierEligibilityClient = courierEligibilityClient;
        this.assignmentAuditRepository = assignmentAuditRepository;
    }

    // --- CourierAssignmentContract Implementation ---

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

    @Override
    @Transactional
    public void closeActiveAssignmentForTerminalParcel(UUID parcelId, Instant endedAt) {
        if (parcelId == null) {
            return;
        }
        assignmentRepository.findByParcelIdAndActiveTrue(parcelId).ifPresent(activeAssignment -> {
            Instant timestamp = endedAt != null ? endedAt : Instant.now();
            activeAssignment.setActive(false);
            activeAssignment.setEndedAt(timestamp);
            assignmentRepository.save(activeAssignment);

            Long version = parcelRepository.findById(parcelId).map(Parcel::getVersion).orElse(1L);
            recordAudit(
                    parcelId,
                    activeAssignment.getAssignmentId(),
                    activeAssignment.getCourierSub(),
                    "TERMINATED",
                    "SYSTEM",
                    "SYSTEM",
                    null,
                    null,
                    "Terminal state transition",
                    version,
                    timestamp
            );
        });
    }

    // --- Own-Assignment Read Implementation (Issue #94) ---

    /**
     * Retrieves the authenticated courier's assignments.
     * Enforces COURIER role, trusted principal derivation, and bounded pagination.
     */
    @Transactional(readOnly = true)
    public PagedResponse<AssignmentResponse> getMyAssignments(
            String courierSub,
            Collection<String> roles,
            boolean activeOnly,
            Pageable pageable) {

        if (courierSub == null || courierSub.isBlank()) {
            throw new AssignmentAccessDeniedException("Authenticated subject is required.");
        }
        if (roles == null || (!roles.contains("COURIER") && !roles.contains("ROLE_COURIER"))) {
            throw new AssignmentAccessDeniedException("Caller does not hold required COURIER role.");
        }

        int pageSize = pageable != null && pageable.isPaged() ? pageable.getPageSize() : 20;
        if (pageSize <= 0) {
            pageSize = 20;
        } else if (pageSize > 100) {
            pageSize = 100;
        }
        int pageNumber = pageable != null && pageable.isPaged() ? Math.max(0, pageable.getPageNumber()) : 0;

        Pageable safePageable = PageRequest.of(
                pageNumber,
                pageSize,
                Sort.by(Sort.Direction.DESC, "assignedAt")
        );

        Page<DeliveryAssignment> page;
        if (activeOnly) {
            page = assignmentRepository.findByCourierSubAndActiveTrueOrderByAssignedAtDesc(
                    courierSub.trim(),
                    safePageable
            );
        } else {
            page = assignmentRepository.findByCourierSubOrderByAssignedAtDesc(
                    courierSub.trim(),
                    safePageable
            );
        }

        Page<AssignmentResponse> dtoPage = page.map(a -> new AssignmentResponse(
                a.getAssignmentId(),
                a.getParcelId(),
                a.getCourierSub(),
                a.isActive(),
                a.getAssignedAt(),
                a.getAssignedBy(),
                null,
                a.getEndedAt(),
                null
        ));

        return PagedResponse.of(dtoPage);
    }

    // --- Initial Assignment Command Implementation (Issue #24) ---

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

        recordAudit(
                parcel.getParcelId(),
                assignmentId,
                request.courierSub().trim(),
                "ASSIGNED",
                dispatcherSub,
                resolveRole(roles),
                null,
                null,
                "Initial assignment",
                newParcelVersion,
                now
        );

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

    // --- Courier Reassignment Command Implementation (Issue #125 / DEC-ASG-06) ---

    /**
     * Atomically deactivates the current courier assignment and creates a new active assignment
     * for an eligible substitute courier.
     *
     * Preconditions enforced:
     * - Actor must hold DISPATCHER or ADMIN role.
     * - Parcel must exist and remain in CREATED state.
     * - Parcel optimistic version must match expectedVersion.
     * - An active assignment must currently exist.
     * - If specified, currentAssignmentId must match the active assignment.
     * - Replacement courier must differ from the currently assigned courier.
     * - Replacement courier must be verified as eligible via Accounts Service.
     * - Atomic commit bumps aggregate version and appends immutable assignment audit.
     */
    @Transactional
    public AssignmentResponse reassignCourier(
            ReassignCourierRequest request,
            String dispatcherSub,
            Collection<String> roles) {

        Objects.requireNonNull(request, "request cannot be null");
        Objects.requireNonNull(dispatcherSub, "dispatcherSub cannot be null");

        // 1. Authorize actor: DISPATCHER or ADMIN role required
        authorizeDispatcher(roles);

        // 2. Retrieve parcel aggregate root
        Parcel parcel = parcelRepository.findById(request.parcelId())
                .orElseThrow(() -> new ParcelNotFoundException(request.parcelId()));

        // 3. State guard (DEC-ASG-02 / DEC-ASG-06): Only parcels in CREATED state can be reassigned
        if (parcel.getStatus() != ParcelStatus.CREATED) {
            throw new InvalidParcelStateException(
                    "Parcel " + parcel.getParcelId() + " is in status " + parcel.getStatus() +
                            " and cannot be reassigned. Reassignment is only permitted while parcel is in CREATED state."
            );
        }

        // 4. Concurrency check: expectedVersion must match parcel.version
        if (!Objects.equals(request.expectedVersion(), parcel.getVersion())) {
            throw new AssignmentConflictException(
                    "Optimistic lock failure: expected parcel version " + request.expectedVersion() +
                            " but was " + parcel.getVersion()
            );
        }

        // 5. Verify current active assignment exists
        DeliveryAssignment currentAssignment = assignmentRepository.findByParcelIdAndActiveTrue(request.parcelId())
                .orElseThrow(() -> new AssignmentConflictException(
                        "Parcel " + request.parcelId() + " does not have an active courier assignment to reassign."
                ));

        // 6. If currentAssignmentId specified, verify exact assignment match
        if (request.currentAssignmentId() != null && !Objects.equals(request.currentAssignmentId(), currentAssignment.getAssignmentId())) {
            throw new AssignmentConflictException(
                    "Expected current assignment ID " + request.currentAssignmentId() +
                            " does not match active assignment " + currentAssignment.getAssignmentId()
            );
        }

        // 7. Verify replacement courier differs from current courier
        if (request.replacementCourierSub() == null || request.replacementCourierSub().isBlank()) {
            throw new IneligibleCourierException("Replacement courier subject cannot be blank.");
        }
        String replacementCourierSub = request.replacementCourierSub().trim();
        if (replacementCourierSub.equals(currentAssignment.getCourierSub())) {
            throw new AssignmentConflictException(
                    "Replacement courier " + replacementCourierSub + " is already actively assigned to parcel " + request.parcelId()
            );
        }

        // 8. Courier business eligibility verification for replacement courier
        CourierEligibilityResult eligibility = courierEligibilityClient.checkEligibility(replacementCourierSub);
        if (!eligibility.isApproved()) {
            throw new IneligibleCourierException(
                    "Replacement courier " + replacementCourierSub + " is not eligible for assignment: " + eligibility.rejectionReason()
            );
        }

        // 9. Atomically deactivate old assignment and create new assignment
        Instant now = Instant.now();
        currentAssignment.setActive(false);
        currentAssignment.setEndedAt(now);
        assignmentRepository.saveAndFlush(currentAssignment);

        UUID newAssignmentId = UUID.randomUUID();
        DeliveryAssignment newAssignment = new DeliveryAssignment(
                newAssignmentId,
                parcel.getParcelId(),
                replacementCourierSub,
                dispatcherSub,
                now,
                null,
                true
        );
        assignmentRepository.saveAndFlush(newAssignment);

        // Bump parcel aggregate version for optimistic locking
        parcel.setVersion(parcel.getVersion() + 1);
        Parcel updatedParcel = parcelRepository.saveAndFlush(parcel);
        Long newParcelVersion = updatedParcel.getVersion();

        recordAudit(
                parcel.getParcelId(),
                newAssignmentId,
                replacementCourierSub,
                "REASSIGNED",
                dispatcherSub,
                resolveRole(roles),
                currentAssignment.getCourierSub(),
                currentAssignment.getAssignmentId(),
                request.reason(),
                newParcelVersion,
                now
        );

        return new AssignmentResponse(
                newAssignmentId,
                parcel.getParcelId(),
                replacementCourierSub,
                true,
                now,
                dispatcherSub,
                newParcelVersion,
                null,
                request.reason()
        );
    }

    // --- Assignment Closure Command Implementation (Issue #125) ---

    /**
     * Closes/ends the active courier assignment for a parcel in CREATED state.
     * Enforces optimistic locking, DISPATCHER/ADMIN authorization, and appends to audit log.
     */
    @Transactional
    public AssignmentResponse closeAssignment(
            CloseAssignmentRequest request,
            String dispatcherSub,
            Collection<String> roles) {

        Objects.requireNonNull(request, "request cannot be null");
        Objects.requireNonNull(dispatcherSub, "dispatcherSub cannot be null");

        // 1. Authorize actor: DISPATCHER or ADMIN role required
        authorizeDispatcher(roles);

        // 2. Retrieve parcel aggregate root
        Parcel parcel = parcelRepository.findById(request.parcelId())
                .orElseThrow(() -> new ParcelNotFoundException(request.parcelId()));

        // 3. State guard: Only parcels in CREATED state can be unassigned by dispatcher
        if (parcel.getStatus() != ParcelStatus.CREATED) {
            throw new InvalidParcelStateException(
                    "Parcel " + parcel.getParcelId() + " is in status " + parcel.getStatus() +
                            " and cannot be unassigned. Assignment closure is only permitted while parcel is in CREATED state."
            );
        }

        // 4. Concurrency check: expectedVersion must match parcel.version
        if (!Objects.equals(request.expectedVersion(), parcel.getVersion())) {
            throw new AssignmentConflictException(
                    "Optimistic lock failure: expected parcel version " + request.expectedVersion() +
                            " but was " + parcel.getVersion()
            );
        }

        // 5. Verify current active assignment exists
        DeliveryAssignment currentAssignment = assignmentRepository.findByParcelIdAndActiveTrue(request.parcelId())
                .orElseThrow(() -> new AssignmentConflictException(
                        "Parcel " + request.parcelId() + " does not have an active courier assignment to close."
                ));

        // 6. If assignmentId specified, verify exact assignment match
        if (request.assignmentId() != null && !Objects.equals(request.assignmentId(), currentAssignment.getAssignmentId())) {
            throw new AssignmentConflictException(
                    "Expected assignment ID " + request.assignmentId() +
                            " does not match active assignment " + currentAssignment.getAssignmentId()
            );
        }

        // 7. Atomically deactivate assignment and bump parcel version
        Instant now = Instant.now();
        currentAssignment.setActive(false);
        currentAssignment.setEndedAt(now);
        assignmentRepository.save(currentAssignment);

        parcel.setVersion(parcel.getVersion() + 1);
        Parcel updatedParcel = parcelRepository.saveAndFlush(parcel);
        Long newParcelVersion = updatedParcel.getVersion();

        recordAudit(
                parcel.getParcelId(),
                currentAssignment.getAssignmentId(),
                currentAssignment.getCourierSub(),
                "CLOSED",
                dispatcherSub,
                resolveRole(roles),
                null,
                null,
                request.reason(),
                newParcelVersion,
                now
        );

        return new AssignmentResponse(
                currentAssignment.getAssignmentId(),
                parcel.getParcelId(),
                currentAssignment.getCourierSub(),
                false,
                currentAssignment.getAssignedAt(),
                currentAssignment.getAssignedBy(),
                newParcelVersion,
                now,
                request.reason()
        );
    }

    // --- Assignment Audit History Query Implementation (Issue #125 / DEC-ASG-07) ---

    /**
     * Retrieves the durable, append-only assignment audit history for a parcel.
     * Restricted to authorized DISPATCHER or ADMIN actors.
     */
    @Transactional(readOnly = true)
    public List<AssignmentAuditResponse> getAssignmentAuditHistory(
            UUID parcelId,
            String actorSub,
            Collection<String> roles) {

        if (parcelId == null) {
            throw new IllegalArgumentException("parcelId cannot be null");
        }
        authorizeDispatcher(roles);

        if (!parcelRepository.existsById(parcelId)) {
            throw new ParcelNotFoundException(parcelId);
        }

        if (assignmentAuditRepository == null) {
            return List.of();
        }

        return assignmentAuditRepository.findByParcelIdOrderByOccurredAtDesc(parcelId)
                .stream()
                .map(a -> new AssignmentAuditResponse(
                        a.getAuditId(),
                        a.getParcelId(),
                        a.getAssignmentId(),
                        a.getCourierSub(),
                        a.getAction(),
                        a.getActorSub(),
                        a.getActorRole(),
                        a.getPreviousCourierSub(),
                        a.getPreviousAssignmentId(),
                        a.getReason(),
                        a.getParcelVersion(),
                        a.getOccurredAt()
                ))
                .toList();
    }

    private void recordAudit(
            UUID parcelId,
            UUID assignmentId,
            String courierSub,
            String action,
            String actorSub,
            String actorRole,
            String previousCourierSub,
            UUID previousAssignmentId,
            String reason,
            Long parcelVersion,
            Instant occurredAt) {
        if (assignmentAuditRepository != null) {
            DeliveryAssignmentAudit audit = new DeliveryAssignmentAudit(
                    UUID.randomUUID(),
                    parcelId,
                    assignmentId,
                    courierSub,
                    action,
                    actorSub,
                    actorRole,
                    previousCourierSub,
                    previousAssignmentId,
                    reason,
                    parcelVersion,
                    occurredAt
            );
            assignmentAuditRepository.save(audit);
        }
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

    private String resolveRole(Collection<String> roles) {
        if (roles != null) {
            if (roles.contains("ROLE_ADMIN") || roles.contains("ADMIN")) {
                return "ADMIN";
            }
            if (roles.contains("ROLE_DISPATCHER") || roles.contains("DISPATCHER")) {
                return "DISPATCHER";
            }
        }
        return "UNKNOWN";
    }
}
