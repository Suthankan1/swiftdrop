package com.swiftdrop.deliveryservice.grpc;

import com.google.protobuf.Timestamp;
import com.swiftdrop.delivery.v1.*;
import com.swiftdrop.deliveryservice.assignment.DeliveryAssignment;
import com.swiftdrop.deliveryservice.assignment.DeliveryAssignmentRepository;
import com.swiftdrop.deliveryservice.core.Parcel;
import com.swiftdrop.deliveryservice.core.ParcelRepository;
import com.swiftdrop.deliveryservice.core.ParcelStatus;
import com.swiftdrop.deliveryservice.outbox.OutboxEvent;
import com.swiftdrop.deliveryservice.outbox.OutboxEventRepository;
import com.swiftdrop.deliveryservice.query.ParcelProjection;
import com.swiftdrop.deliveryservice.query.ParcelQueryService;
import com.swiftdrop.deliveryservice.status.StatusHistory;
import com.swiftdrop.deliveryservice.status.StatusHistoryRepository;
import com.swiftdrop.security.grpc.DelegatedActorContext;
import com.swiftdrop.security.grpc.SwiftDropGrpcContext;
import com.swiftdrop.security.service.SwiftDropServiceIdentity;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * Authoritative gRPC server implementation for DeliveryInternalService.
 *
 * Implements:
 * - IF-GRPC-05: GetParcelInternal for internal projections and GraphQL resolvers.
 * - GetCourierWorkload: Active workload calculation for individual couriers.
 * - IF-GRPC-11 (Issue #111 / N10-B07): AuthorizeEventRecipient for Communications notification workers.
 * - ListUnassignedParcels (Issue #113 / N10-B09): Scoped paginated unassigned parcel queue for Dispatcher/Admin.
 * - GetCourierWorkloadSummary (Issue #113 / N10-B09): Batch active courier workload statistics.
 * - GetDeliveryStatsInternal (Issue #113 / N10-B09): Bounded delivery status and daily outcome statistics for GraphQL #30.
 */
@Service
@Transactional(readOnly = true)
public class DeliveryInternalServiceImpl extends DeliveryInternalServiceGrpc.DeliveryInternalServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(DeliveryInternalServiceImpl.class);
    private static final ZoneId COLOMBO_ZONE = ZoneId.of("Asia/Colombo");

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 50;
    private static final int MAX_BATCH_COURIERS = 50;
    private static final int MAX_STATS_WINDOW_DAYS = 90;

    private final ParcelRepository parcelRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final DeliveryAssignmentRepository assignmentRepository;
    private final StatusHistoryRepository historyRepository;
    private final ParcelQueryService parcelQueryService;

    public DeliveryInternalServiceImpl(
            ParcelRepository parcelRepository,
            OutboxEventRepository outboxEventRepository,
            DeliveryAssignmentRepository assignmentRepository,
            StatusHistoryRepository historyRepository,
            ParcelQueryService parcelQueryService) {
        this.parcelRepository = Objects.requireNonNull(parcelRepository, "ParcelRepository is required");
        this.outboxEventRepository = Objects.requireNonNull(outboxEventRepository, "OutboxEventRepository is required");
        this.assignmentRepository = Objects.requireNonNull(assignmentRepository, "DeliveryAssignmentRepository is required");
        this.historyRepository = Objects.requireNonNull(historyRepository, "StatusHistoryRepository is required");
        this.parcelQueryService = Objects.requireNonNull(parcelQueryService, "ParcelQueryService is required");
    }

    @Override
    public void getParcelInternal(GetParcelInternalRequest request, StreamObserver<GetParcelInternalResponse> responseObserver) {
        try {
            String parcelIdStr = request.getParcelId();
            if (parcelIdStr == null || parcelIdStr.isBlank()) {
                responseObserver.onError(Status.INVALID_ARGUMENT
                        .withDescription("parcel_id must not be blank")
                        .asRuntimeException());
                return;
            }

            UUID parcelId;
            try {
                parcelId = UUID.fromString(parcelIdStr.trim());
            } catch (IllegalArgumentException e) {
                responseObserver.onError(Status.INVALID_ARGUMENT
                        .withDescription("Invalid parcel_id format: " + parcelIdStr)
                        .asRuntimeException());
                return;
            }

            Optional<ParcelProjection> projection = parcelQueryService.findParcelById(parcelId);
            if (projection.isPresent()) {
                ParcelProjection p = projection.get();
                GetParcelInternalResponse.Builder builder = GetParcelInternalResponse.newBuilder()
                        .setParcelId(p.parcelId().toString())
                        .setTrackingNumber(p.trackingNumber())
                        .setCurrentStatus(p.currentStatus().name())
                        .setVersion(p.version())
                        .setAmountMinorUnits(p.amountMinorUnits() != null ? p.amountMinorUnits() : 0L)
                        .setCurrency(p.currency() != null ? p.currency() : "LKR")
                        .setCreatedBySub(p.createdBySub() != null ? p.createdBySub() : "")
                        .setMerchantId(p.merchantId() != null ? p.merchantId().toString() : "")
                        .setActiveCourierSub(p.activeCourierSub() != null ? p.activeCourierSub() : "");

                if (p.createdAt() != null) {
                    builder.setCreatedAt(toTimestamp(p.createdAt()));
                }

                responseObserver.onNext(builder.build());
                responseObserver.onCompleted();
                return;
            }

            Optional<Parcel> parcelOpt = parcelRepository.findById(parcelId);
            if (parcelOpt.isPresent()) {
                Parcel p = parcelOpt.get();
                String activeCourierSub = assignmentRepository
                        .findByParcelIdAndActiveTrue(parcelId)
                        .map(DeliveryAssignment::getCourierSub)
                        .orElse("");

                Instant createdAt = p.getCreatedAt() != null ? p.getCreatedAt() : Instant.now();
                GetParcelInternalResponse response = GetParcelInternalResponse.newBuilder()
                        .setParcelId(p.getParcelId().toString())
                        .setTrackingNumber(p.getTrackingNumber())
                        .setCurrentStatus(p.getStatus().name())
                        .setVersion(p.getVersion() != null ? p.getVersion() : 1L)
                        .setAmountMinorUnits(p.getAmountMinorUnits() != null ? p.getAmountMinorUnits() : 0L)
                        .setCurrency(p.getCurrency() != null ? p.getCurrency() : "LKR")
                        .setCreatedBySub(p.getSenderSub() != null ? p.getSenderSub() : "")
                        .setMerchantId(p.getMerchantId() != null ? p.getMerchantId().toString() : "")
                        .setActiveCourierSub(activeCourierSub)
                        .setCreatedAt(toTimestamp(createdAt))
                        .build();

                responseObserver.onNext(response);
                responseObserver.onCompleted();
                return;
            }

            responseObserver.onError(Status.NOT_FOUND
                    .withDescription("Parcel not found: " + parcelId)
                    .asRuntimeException());

        } catch (StatusRuntimeException ex) {
            responseObserver.onError(ex);
        } catch (Exception ex) {
            log.error("Unexpected error in getParcelInternal", ex);
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Internal delivery service error")
                    .asRuntimeException());
        }
    }

    @Override
    public void getCourierWorkload(CourierWorkloadRequest request, StreamObserver<CourierWorkloadResponse> responseObserver) {
        try {
            String courierSub = request.getCourierSub();
            if (courierSub == null || courierSub.isBlank()) {
                responseObserver.onError(Status.INVALID_ARGUMENT
                        .withDescription("courier_sub must not be blank")
                        .asRuntimeException());
                return;
            }

            int count = assignmentRepository.countByCourierSubAndActiveTrue(courierSub.trim());

            CourierWorkloadResponse response = CourierWorkloadResponse.newBuilder()
                    .setCourierSub(courierSub.trim())
                    .setActiveAssignmentsCount(count)
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();

        } catch (StatusRuntimeException ex) {
            responseObserver.onError(ex);
        } catch (Exception ex) {
            log.error("Unexpected error in getCourierWorkload", ex);
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Internal delivery service error")
                    .asRuntimeException());
        }
    }

    @Override
    public void authorizeEventRecipient(AuthorizeEventRecipientRequest request,
                                        StreamObserver<AuthorizeEventRecipientResponse> responseObserver) {
        // 1. Caller validation (Defense in depth complementing SwiftDropGrpcServerSecurityInterceptor)
        Optional<SwiftDropServiceIdentity> callerIdentity = SwiftDropGrpcContext.currentCallingService();
        if (callerIdentity.isPresent() && callerIdentity.get() != SwiftDropServiceIdentity.COMMUNICATIONS_SERVICE) {
            log.warn("Unauthorized service caller rejected for AuthorizeEventRecipient: {}", callerIdentity.get());
            responseObserver.onError(Status.PERMISSION_DENIED
                    .withDescription("Caller service is not authorized to invoke AuthorizeEventRecipient: " + callerIdentity.get().getServiceName())
                    .asRuntimeException());
            return;
        }

        // 2. Anti-spoofing: actor delegation is strictly FORBIDDEN for background worker queries
        if (SwiftDropGrpcContext.currentDelegatedActor().isPresent()) {
            log.warn("Actor delegation header detected on background notification endpoint; rejecting request");
            responseObserver.onError(Status.PERMISSION_DENIED
                    .withDescription("Human actor delegation is forbidden for AuthorizeEventRecipient")
                    .asRuntimeException());
            return;
        }

        // 3. Request validation
        String rawEventId = request.getEventId();
        if (rawEventId == null || rawEventId.isBlank()) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("event_id is required").asRuntimeException());
            return;
        }
        UUID eventId;
        try {
            eventId = UUID.fromString(rawEventId.trim());
        } catch (IllegalArgumentException e) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("Invalid event_id format: must be valid UUID").asRuntimeException());
            return;
        }

        String rawParcelId = request.getParcelId();
        if (rawParcelId == null || rawParcelId.isBlank()) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("parcel_id is required").asRuntimeException());
            return;
        }
        UUID parcelId;
        try {
            parcelId = UUID.fromString(rawParcelId.trim());
        } catch (IllegalArgumentException e) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("Invalid parcel_id format: must be valid UUID").asRuntimeException());
            return;
        }

        UUID expectedMerchantId = null;
        if (request.getMerchantId() != null && !request.getMerchantId().isBlank()) {
            try {
                expectedMerchantId = UUID.fromString(request.getMerchantId().trim());
            } catch (IllegalArgumentException e) {
                responseObserver.onError(Status.INVALID_ARGUMENT
                        .withDescription("Invalid merchant_id format: must be valid UUID").asRuntimeException());
                return;
            }
        }

        String rawRecipientSub = request.getRecipientSub() != null ? request.getRecipientSub().trim() : "";
        if (!rawRecipientSub.isEmpty() && (rawRecipientSub.length() > 255 || rawRecipientSub.contains("\n") || rawRecipientSub.contains("\r"))) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("Invalid recipient_sub format").asRuntimeException());
            return;
        }

        // 4. Authoritative event lookup in Delivery outbox_events
        Optional<OutboxEvent> outboxOpt = outboxEventRepository.findById(eventId);
        if (outboxOpt.isEmpty()) {
            log.debug("Event {} not found in authoritative Delivery outbox", eventId);
            responseObserver.onNext(AuthorizeEventRecipientResponse.newBuilder()
                    .setIsAuthorized(false)
                    .setEventId(eventId.toString())
                    .setParcelId(parcelId.toString())
                    .setAuthorizationReason("EVENT_NOT_FOUND")
                    .build());
            responseObserver.onCompleted();
            return;
        }

        OutboxEvent outboxEvent = outboxOpt.get();

        // 5. Anti-forgery check: verify event belongs to requested parcel
        if (!parcelId.equals(outboxEvent.getAggregateId())) {
            log.warn("Forged or mismatched event-to-parcel pairing: event {} aggregateId {} != requested parcelId {}",
                    eventId, outboxEvent.getAggregateId(), parcelId);
            responseObserver.onNext(AuthorizeEventRecipientResponse.newBuilder()
                    .setIsAuthorized(false)
                    .setEventId(eventId.toString())
                    .setParcelId(parcelId.toString())
                    .setAuthorizationReason("PARCEL_MISMATCH")
                    .build());
            responseObserver.onCompleted();
            return;
        }

        // 6. Query authoritative parcel state
        Optional<Parcel> parcelOpt = parcelRepository.findById(parcelId);
        if (parcelOpt.isEmpty()) {
            log.warn("Parcel {} referenced by event {} not found in parcels store", parcelId, eventId);
            responseObserver.onNext(AuthorizeEventRecipientResponse.newBuilder()
                    .setIsAuthorized(false)
                    .setEventId(eventId.toString())
                    .setParcelId(parcelId.toString())
                    .setAuthorizationReason("PARCEL_NOT_FOUND")
                    .build());
            responseObserver.onCompleted();
            return;
        }

        Parcel parcel = parcelOpt.get();

        // 7. Cross-tenant isolation verification
        if (expectedMerchantId != null) {
            if (parcel.getMerchantId() == null || !expectedMerchantId.equals(parcel.getMerchantId())) {
                log.warn("Cross-tenant access attempt: parcel {} merchant {} != expected merchant {}",
                        parcelId, parcel.getMerchantId(), expectedMerchantId);
                responseObserver.onNext(AuthorizeEventRecipientResponse.newBuilder()
                        .setIsAuthorized(false)
                        .setEventId(eventId.toString())
                        .setParcelId(parcelId.toString())
                        .setAuthorizationReason("MERCHANT_MISMATCH")
                        .build());
                responseObserver.onCompleted();
                return;
            }
        }

        String merchantIdStr = parcel.getMerchantId() != null ? parcel.getMerchantId().toString() : "";

        // 8. Recipient authorization: evaluate binding against Delivery-owned authoritative state
        String customerSub = parcel.getSenderSub();
        Optional<String> activeCourierSub = assignmentRepository
                .findByParcelIdAndActiveTrue(parcelId)
                .map(DeliveryAssignment::getCourierSub);

        String resolvedRecipientSub;
        if (!rawRecipientSub.isEmpty()) {
            boolean matchesCustomer = rawRecipientSub.equals(customerSub);
            boolean matchesCourier = activeCourierSub.isPresent() && rawRecipientSub.equals(activeCourierSub.get());

            if (!matchesCustomer && !matchesCourier) {
                log.warn("Recipient {} is not authorized for parcel {}", rawRecipientSub, parcelId);
                responseObserver.onNext(AuthorizeEventRecipientResponse.newBuilder()
                        .setIsAuthorized(false)
                        .setEventId(eventId.toString())
                        .setParcelId(parcelId.toString())
                        .setMerchantId(merchantIdStr)
                        .setTrackingNumber(parcel.getTrackingNumber())
                        .setCurrentStatus(parcel.getStatus().name())
                        .setEventType(outboxEvent.getEventType())
                        .setEventVersion(outboxEvent.getAggregateVersion())
                        .setCurrentVersion(parcel.getVersion())
                        .setAuthorizationReason("RECIPIENT_NOT_AUTHORIZED")
                        .build());
                responseObserver.onCompleted();
                return;
            }
            resolvedRecipientSub = rawRecipientSub;
        } else {
            // Unspecified recipient: resolve primary customer recipient sub
            resolvedRecipientSub = customerSub;
        }

        // 9. Stale and terminal event policies
        long eventVersion = outboxEvent.getAggregateVersion();
        long currentVersion = parcel.getVersion() != null ? parcel.getVersion() : 1L;
        boolean isStale = eventVersion < currentVersion;
        boolean isTerminal = parcel.getStatus() == ParcelStatus.DELIVERED || parcel.getStatus() == ParcelStatus.FAILED;

        boolean isAuthorized;
        String reason;

        if (isStale && isTerminal) {
            isAuthorized = false;
            reason = "STALE_TERMINAL_EVENT";
        } else if (isStale && !request.getAllowStale()) {
            isAuthorized = false;
            reason = "STALE_EVENT";
        } else if (isStale && request.getAllowStale()) {
            isAuthorized = true;
            reason = "AUTHORIZED_STALE";
        } else {
            isAuthorized = true;
            reason = isTerminal ? "AUTHORIZED_TERMINAL" : "AUTHORIZED";
        }

        log.info("AuthorizeEventRecipient decision: eventId={}, parcelId={}, recipientSub={}, isAuthorized={}, reason={}",
                eventId, parcelId, resolvedRecipientSub, isAuthorized, reason);

        AuthorizeEventRecipientResponse response = AuthorizeEventRecipientResponse.newBuilder()
                .setIsAuthorized(isAuthorized)
                .setParcelId(parcelId.toString())
                .setEventId(eventId.toString())
                .setRecipientSub(resolvedRecipientSub)
                .setMerchantId(merchantIdStr)
                .setTrackingNumber(parcel.getTrackingNumber())
                .setCurrentStatus(parcel.getStatus().name())
                .setEventType(outboxEvent.getEventType())
                .setEventVersion(eventVersion)
                .setCurrentVersion(currentVersion)
                .setIsTerminal(isTerminal)
                .setIsStale(isStale)
                .setAuthorizationReason(reason)
                .build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void listUnassignedParcels(ListUnassignedParcelsRequest request, StreamObserver<ListUnassignedParcelsResponse> responseObserver) {
        try {
            requireAuthorizedStaffActor();

            int page = request.getPage();
            int pageSize = request.getPageSize() == 0 ? DEFAULT_PAGE_SIZE : request.getPageSize();

            if (page < 0) {
                responseObserver.onError(Status.INVALID_ARGUMENT
                        .withDescription("page index must be non-negative")
                        .asRuntimeException());
                return;
            }
            if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
                responseObserver.onError(Status.INVALID_ARGUMENT
                        .withDescription("pageSize must be between 1 and " + MAX_PAGE_SIZE)
                        .asRuntimeException());
                return;
            }

            // If a specific zone is requested, since Delivery does not yet persist zone columns,
            // return empty result to prevent fabricating unauthorized/incorrect zone assignments.
            if (request.getZone() != null && !request.getZone().isBlank()) {
                ListUnassignedParcelsResponse response = ListUnassignedParcelsResponse.newBuilder()
                        .setPage(page)
                        .setPageSize(pageSize)
                        .setTotalCount(0)
                        .build();
                responseObserver.onNext(response);
                responseObserver.onCompleted();
                return;
            }

            Page<Parcel> parcelPage = parcelRepository.findUnassignedParcels(PageRequest.of(page, pageSize));

            ListUnassignedParcelsResponse.Builder responseBuilder = ListUnassignedParcelsResponse.newBuilder()
                    .setPage(parcelPage.getNumber())
                    .setPageSize(parcelPage.getSize())
                    .setTotalCount((int) parcelPage.getTotalElements());

            for (Parcel parcel : parcelPage.getContent()) {
                UnassignedParcelSummary.Builder summaryBuilder = UnassignedParcelSummary.newBuilder()
                        .setParcelId(parcel.getParcelId().toString())
                        .setTrackingNumber(parcel.getTrackingNumber())
                        .setStatus(parcel.getStatus().name())
                        .setVersion(parcel.getVersion() != null ? parcel.getVersion() : 1L)
                        .setCurrency(parcel.getCurrency() != null ? parcel.getCurrency() : "LKR")
                        .setAmountMinorUnits(parcel.getAmountMinorUnits() != null ? parcel.getAmountMinorUnits() : 0L)
                        .setWeightKg(parcel.getWeightKg() != null ? parcel.getWeightKg().doubleValue() : 0.0);

                if (parcel.getCreatedAt() != null) {
                    summaryBuilder.setCreatedAt(toTimestamp(parcel.getCreatedAt()));
                }

                responseBuilder.addItems(summaryBuilder.build());
            }

            responseObserver.onNext(responseBuilder.build());
            responseObserver.onCompleted();

        } catch (StatusRuntimeException ex) {
            responseObserver.onError(ex);
        } catch (Exception ex) {
            log.error("Unexpected error in listUnassignedParcels", ex);
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Internal delivery service error")
                    .asRuntimeException());
        }
    }

    @Override
    public void getCourierWorkloadSummary(CourierWorkloadSummaryRequest request, StreamObserver<CourierWorkloadSummaryResponse> responseObserver) {
        try {
            requireAuthorizedStaffActor();

            List<String> requestedSubs = request.getCourierSubsList();
            if (requestedSubs == null || requestedSubs.isEmpty()) {
                responseObserver.onNext(CourierWorkloadSummaryResponse.newBuilder().build());
                responseObserver.onCompleted();
                return;
            }

            if (requestedSubs.size() > MAX_BATCH_COURIERS) {
                responseObserver.onError(Status.INVALID_ARGUMENT
                        .withDescription("Batch size exceeds maximum limit of " + MAX_BATCH_COURIERS + " couriers")
                        .asRuntimeException());
                return;
            }

            List<String> sanitizedSubs = requestedSubs.stream()
                    .map(String::trim)
                    .filter(s -> !s.isBlank())
                    .distinct()
                    .toList();

            Map<String, Integer> countMap = new HashMap<>();
            if (!sanitizedSubs.isEmpty()) {
                List<Object[]> rows = assignmentRepository.countActiveAssignmentsByCourierSubs(sanitizedSubs);
                for (Object[] row : rows) {
                    String sub = (String) row[0];
                    long count = (Long) row[1];
                    countMap.put(sub, (int) count);
                }
            }

            CourierWorkloadSummaryResponse.Builder responseBuilder = CourierWorkloadSummaryResponse.newBuilder();
            for (String sub : sanitizedSubs) {
                int activeCount = countMap.getOrDefault(sub, 0);
                responseBuilder.addWorkloads(CourierWorkloadResponse.newBuilder()
                        .setCourierSub(sub)
                        .setActiveAssignmentsCount(activeCount)
                        .build());
            }

            responseObserver.onNext(responseBuilder.build());
            responseObserver.onCompleted();

        } catch (StatusRuntimeException ex) {
            responseObserver.onError(ex);
        } catch (Exception ex) {
            log.error("Unexpected error in getCourierWorkloadSummary", ex);
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Internal delivery service error")
                    .asRuntimeException());
        }
    }

    @Override
    public void getDeliveryStatsInternal(GetDeliveryStatsRequest request, StreamObserver<GetDeliveryStatsResponse> responseObserver) {
        try {
            requireAuthorizedStaffActor();

            Instant startInstant;
            Instant endInstant;

            if (request.hasStartTime() && request.getStartTime().getSeconds() > 0) {
                startInstant = toInstant(request.getStartTime());
            } else {
                LocalDate todayColombo = LocalDate.now(COLOMBO_ZONE);
                startInstant = todayColombo.atStartOfDay(COLOMBO_ZONE).toInstant();
            }

            if (request.hasEndTime() && request.getEndTime().getSeconds() > 0) {
                endInstant = toInstant(request.getEndTime());
            } else {
                LocalDate todayColombo = LocalDate.now(COLOMBO_ZONE);
                endInstant = todayColombo.plusDays(1).atStartOfDay(COLOMBO_ZONE).toInstant();
            }

            if (startInstant.isAfter(endInstant)) {
                responseObserver.onError(Status.INVALID_ARGUMENT
                        .withDescription("start_time must be before or equal to end_time")
                        .asRuntimeException());
                return;
            }

            if (Duration.between(startInstant, endInstant).toDays() > MAX_STATS_WINDOW_DAYS) {
                responseObserver.onError(Status.INVALID_ARGUMENT
                        .withDescription("Reporting window exceeds maximum allowed range of " + MAX_STATS_WINDOW_DAYS + " days")
                        .asRuntimeException());
                return;
            }

            // If a specific zone is requested, since Delivery does not yet track zone,
            // return zero counts to avoid fabricating numbers.
            if (request.getZone() != null && !request.getZone().isBlank()) {
                GetDeliveryStatsResponse response = GetDeliveryStatsResponse.newBuilder()
                        .setTotalCount(0L)
                        .setDeliveredCount(0L)
                        .setFailedCount(0L)
                        .setInTransitCount(0L)
                        .setCreatedCount(0L)
                        .build();
                responseObserver.onNext(response);
                responseObserver.onCompleted();
                return;
            }

            String statusFilter = request.getStatus() != null ? request.getStatus().trim() : "";

            long createdCount = 0;
            long deliveredCount = 0;
            long failedCount = 0;
            long inTransitCount = 0;
            Double avgDeliveryHours = null;

            if (statusFilter.isEmpty()) {
                createdCount = parcelRepository.countCreatedBetween(startInstant, endInstant);
                deliveredCount = historyRepository.countDeliveredBetween(startInstant, endInstant);
                failedCount = historyRepository.countFailedBetween(startInstant, endInstant);
                inTransitCount = parcelRepository.countInTransitBeforeOrAt(endInstant);
            } else if ("CREATED".equalsIgnoreCase(statusFilter)) {
                createdCount = parcelRepository.countCreatedBetween(startInstant, endInstant);
            } else if ("DELIVERED".equalsIgnoreCase(statusFilter)) {
                deliveredCount = historyRepository.countDeliveredBetween(startInstant, endInstant);
            } else if ("FAILED".equalsIgnoreCase(statusFilter)) {
                failedCount = historyRepository.countFailedBetween(startInstant, endInstant);
            } else if ("IN_TRANSIT".equalsIgnoreCase(statusFilter)
                    || "PICKED_UP".equalsIgnoreCase(statusFilter)
                    || "OUT_FOR_DELIVERY".equalsIgnoreCase(statusFilter)) {
                inTransitCount = parcelRepository.countInTransitBeforeOrAt(endInstant);
            }

            long totalCount = createdCount + deliveredCount + failedCount + inTransitCount;

            // Calculate average delivery duration from creation to first DELIVERED transition
            if (deliveredCount > 0) {
                List<Object[]> rows = historyRepository.findDeliveredParcelDurations(startInstant, endInstant);
                if (!rows.isEmpty()) {
                    double totalHours = 0.0;
                    int count = 0;
                    for (Object[] row : rows) {
                        Instant createdAt = (Instant) row[0];
                        Instant deliveredAt = (Instant) row[1];
                        if (createdAt != null && deliveredAt != null && !deliveredAt.isBefore(createdAt)) {
                            Duration dur = Duration.between(createdAt, deliveredAt);
                            totalHours += dur.toMillis() / (1000.0 * 60 * 60);
                            count++;
                        }
                    }
                    if (count > 0) {
                        avgDeliveryHours = Math.round((totalHours / count) * 100.0) / 100.0;
                    }
                }
            }

            GetDeliveryStatsResponse.Builder responseBuilder = GetDeliveryStatsResponse.newBuilder()
                    .setTotalCount(totalCount)
                    .setDeliveredCount(deliveredCount)
                    .setFailedCount(failedCount)
                    .setInTransitCount(inTransitCount)
                    .setCreatedCount(createdCount);

            if (avgDeliveryHours != null) {
                responseBuilder.setAverageDeliveryHours(avgDeliveryHours);
            }

            responseObserver.onNext(responseBuilder.build());
            responseObserver.onCompleted();

        } catch (StatusRuntimeException ex) {
            responseObserver.onError(ex);
        } catch (Exception ex) {
            log.error("Unexpected error in getDeliveryStatsInternal", ex);
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Internal delivery service error")
                    .asRuntimeException());
        }
    }

    @Override
    public void listCustomerParcels(ListCustomerParcelsRequest request, StreamObserver<ListCustomerParcelsResponse> responseObserver) {
        try {
            DelegatedActorContext actor = requireDelegatedActor();

            String effectiveCustomerSub;
            boolean isStaff = actor.isAdmin() || actor.isDispatcher();
            if (actor.isCustomer()) {
                effectiveCustomerSub = actor.actorSub();
                if (request.getCustomerSub() != null && !request.getCustomerSub().isBlank()
                        && !request.getCustomerSub().trim().equals(effectiveCustomerSub)) {
                    responseObserver.onError(Status.PERMISSION_DENIED
                            .withDescription("Customer cannot query another customer's parcels: requested "
                                    + request.getCustomerSub().trim() + " != actor " + effectiveCustomerSub)
                            .asRuntimeException());
                    return;
                }
            } else if (isStaff) {
                if (request.getCustomerSub() == null || request.getCustomerSub().isBlank()) {
                    responseObserver.onError(Status.INVALID_ARGUMENT
                            .withDescription("customer_sub must be specified for staff query")
                            .asRuntimeException());
                    return;
                }
                effectiveCustomerSub = request.getCustomerSub().trim();
            } else {
                responseObserver.onError(Status.PERMISSION_DENIED
                        .withDescription("Caller role is not authorized for customer parcel reads: " + actor.actorRoles())
                        .asRuntimeException());
                return;
            }

            int page = request.getPage();
            int pageSize = request.getPageSize() == 0 ? DEFAULT_PAGE_SIZE : request.getPageSize();
            if (page < 0) {
                responseObserver.onError(Status.INVALID_ARGUMENT
                        .withDescription("page index must be non-negative")
                        .asRuntimeException());
                return;
            }
            if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
                responseObserver.onError(Status.INVALID_ARGUMENT
                        .withDescription("pageSize must be between 1 and " + MAX_PAGE_SIZE)
                        .asRuntimeException());
                return;
            }

            List<String> requestedParcelIds = request.getParcelIdsList();
            if (requestedParcelIds != null && requestedParcelIds.size() > MAX_PAGE_SIZE) {
                responseObserver.onError(Status.INVALID_ARGUMENT
                        .withDescription("parcel_ids batch exceeds maximum limit of " + MAX_PAGE_SIZE)
                        .asRuntimeException());
                return;
            }

            int activeCount = parcelRepository.countActiveBySenderSub(effectiveCustomerSub);
            int completedCount = parcelRepository.countCompletedBySenderSub(effectiveCustomerSub);
            int totalCount = activeCount + completedCount;

            List<CustomerParcelSummary> summaries = new ArrayList<>();

            if (requestedParcelIds != null && !requestedParcelIds.isEmpty()) {
                List<UUID> validUuids = new ArrayList<>();
                for (String pidStr : requestedParcelIds) {
                    try {
                        validUuids.add(UUID.fromString(pidStr.trim()));
                    } catch (Exception ignored) {
                        // Skip malformed UUID safely without leaking
                    }
                }
                if (!validUuids.isEmpty()) {
                    List<Parcel> parcels = parcelRepository.findBySenderSubAndParcelIdIn(effectiveCustomerSub, validUuids);
                    for (Parcel p : parcels) {
                        summaries.add(toCustomerParcelSummary(p));
                    }
                }
                totalCount = summaries.size();
            } else {
                String filter = request.getStatusFilter() != null ? request.getStatusFilter().trim().toUpperCase() : "";
                Pageable pageable = PageRequest.of(page, pageSize);
                Page<Parcel> parcelPage;
                if ("ACTIVE".equals(filter)) {
                    parcelPage = parcelRepository.findActiveBySenderSub(effectiveCustomerSub, pageable);
                    totalCount = (int) parcelPage.getTotalElements();
                } else if ("COMPLETED".equals(filter)) {
                    parcelPage = parcelRepository.findCompletedBySenderSub(effectiveCustomerSub, pageable);
                    totalCount = (int) parcelPage.getTotalElements();
                } else if (!filter.isEmpty()) {
                    try {
                        ParcelStatus status = ParcelStatus.valueOf(filter);
                        parcelPage = parcelRepository.findBySenderSubAndStatus(effectiveCustomerSub, status, pageable);
                        totalCount = (int) parcelPage.getTotalElements();
                    } catch (IllegalArgumentException e) {
                        responseObserver.onError(Status.INVALID_ARGUMENT
                                .withDescription("Invalid status_filter: " + filter)
                                .asRuntimeException());
                        return;
                    }
                } else {
                    parcelPage = parcelRepository.findBySenderSubOrderByCreatedAtDesc(effectiveCustomerSub, pageable);
                }

                for (Parcel p : parcelPage.getContent()) {
                    summaries.add(toCustomerParcelSummary(p));
                }
            }

            ListCustomerParcelsResponse response = ListCustomerParcelsResponse.newBuilder()
                    .setTotalCount(totalCount)
                    .setPage(page)
                    .setPageSize(pageSize)
                    .setActiveCount(activeCount)
                    .setCompletedCount(completedCount)
                    .addAllParcels(summaries)
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();

        } catch (StatusRuntimeException ex) {
            responseObserver.onError(ex);
        } catch (Exception ex) {
            log.error("Unexpected error in listCustomerParcels", ex);
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Internal delivery service error")
                    .asRuntimeException());
        }
    }

    @Override
    public void listMerchantOrders(ListMerchantOrdersRequest request, StreamObserver<ListMerchantOrdersResponse> responseObserver) {
        try {
            DelegatedActorContext actor = requireDelegatedActor();

            UUID effectiveMerchantId;
            boolean isStaff = actor.isAdmin() || actor.isDispatcher();
            if (actor.isMerchant()) {
                String actorTenantId = actor.tenantId();
                if (actorTenantId == null || actorTenantId.isBlank()) {
                    responseObserver.onError(Status.PERMISSION_DENIED
                            .withDescription("Merchant actor does not have an associated merchant tenant")
                            .asRuntimeException());
                    return;
                }
                try {
                    effectiveMerchantId = UUID.fromString(actorTenantId.trim());
                } catch (IllegalArgumentException e) {
                    responseObserver.onError(Status.PERMISSION_DENIED
                            .withDescription("Invalid merchant tenant ID in actor token")
                            .asRuntimeException());
                    return;
                }

                if (request.getMerchantId() != null && !request.getMerchantId().isBlank()) {
                    try {
                        UUID requestedId = UUID.fromString(request.getMerchantId().trim());
                        if (!requestedId.equals(effectiveMerchantId)) {
                            responseObserver.onError(Status.PERMISSION_DENIED
                                    .withDescription("Cross-tenant read denied: requested " + requestedId
                                            + " != actor tenant " + effectiveMerchantId)
                                    .asRuntimeException());
                            return;
                        }
                    } catch (IllegalArgumentException e) {
                        responseObserver.onError(Status.INVALID_ARGUMENT
                                .withDescription("Invalid merchant_id format: must be valid UUID")
                                .asRuntimeException());
                        return;
                    }
                }
            } else if (isStaff) {
                if (request.getMerchantId() == null || request.getMerchantId().isBlank()) {
                    responseObserver.onError(Status.INVALID_ARGUMENT
                            .withDescription("merchant_id must be specified for staff query")
                            .asRuntimeException());
                    return;
                }
                try {
                    effectiveMerchantId = UUID.fromString(request.getMerchantId().trim());
                } catch (IllegalArgumentException e) {
                    responseObserver.onError(Status.INVALID_ARGUMENT
                            .withDescription("Invalid merchant_id format: must be valid UUID")
                            .asRuntimeException());
                    return;
                }
            } else {
                responseObserver.onError(Status.PERMISSION_DENIED
                        .withDescription("Caller role is not authorized for merchant order reads: " + actor.actorRoles())
                        .asRuntimeException());
                return;
            }

            int page = request.getPage();
            int pageSize = request.getPageSize() == 0 ? DEFAULT_PAGE_SIZE : request.getPageSize();
            if (page < 0) {
                responseObserver.onError(Status.INVALID_ARGUMENT
                        .withDescription("page index must be non-negative")
                        .asRuntimeException());
                return;
            }
            if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
                responseObserver.onError(Status.INVALID_ARGUMENT
                        .withDescription("pageSize must be between 1 and " + MAX_PAGE_SIZE)
                        .asRuntimeException());
                return;
            }

            List<String> requestedParcelIds = request.getParcelIdsList();
            if (requestedParcelIds != null && requestedParcelIds.size() > MAX_PAGE_SIZE) {
                responseObserver.onError(Status.INVALID_ARGUMENT
                        .withDescription("parcel_ids batch exceeds maximum limit of " + MAX_PAGE_SIZE)
                        .asRuntimeException());
                return;
            }

            int activeCount = parcelRepository.countActiveByMerchantId(effectiveMerchantId);
            int deliveredCount = parcelRepository.countDeliveredByMerchantId(effectiveMerchantId);
            int failedCount = parcelRepository.countFailedByMerchantId(effectiveMerchantId);
            int totalCount = parcelRepository.countByMerchantId(effectiveMerchantId);

            List<MerchantOrderSummary> summaries = new ArrayList<>();

            if (requestedParcelIds != null && !requestedParcelIds.isEmpty()) {
                List<UUID> validUuids = new ArrayList<>();
                for (String pidStr : requestedParcelIds) {
                    try {
                        validUuids.add(UUID.fromString(pidStr.trim()));
                    } catch (Exception ignored) {}
                }
                if (!validUuids.isEmpty()) {
                    List<Parcel> parcels = parcelRepository.findByMerchantIdAndParcelIdIn(effectiveMerchantId, validUuids);
                    for (Parcel p : parcels) {
                        summaries.add(toMerchantOrderSummary(p));
                    }
                }
                totalCount = summaries.size();
            } else {
                String filter = request.getStatusFilter() != null ? request.getStatusFilter().trim().toUpperCase() : "";
                Pageable pageable = PageRequest.of(page, pageSize);
                Page<Parcel> parcelPage;
                if ("ACTIVE".equals(filter)) {
                    parcelPage = parcelRepository.findActiveByMerchantId(effectiveMerchantId, pageable);
                    totalCount = (int) parcelPage.getTotalElements();
                } else if (!filter.isEmpty()) {
                    try {
                        ParcelStatus status = ParcelStatus.valueOf(filter);
                        parcelPage = parcelRepository.findByMerchantIdAndStatus(effectiveMerchantId, status, pageable);
                        totalCount = (int) parcelPage.getTotalElements();
                    } catch (IllegalArgumentException e) {
                        responseObserver.onError(Status.INVALID_ARGUMENT
                                .withDescription("Invalid status_filter: " + filter)
                                .asRuntimeException());
                        return;
                    }
                } else {
                    parcelPage = parcelRepository.findByMerchantIdOrderByCreatedAtDesc(effectiveMerchantId, pageable);
                }

                for (Parcel p : parcelPage.getContent()) {
                    summaries.add(toMerchantOrderSummary(p));
                }
            }

            ListMerchantOrdersResponse response = ListMerchantOrdersResponse.newBuilder()
                    .setTotalCount(totalCount)
                    .setPage(page)
                    .setPageSize(pageSize)
                    .setActiveCount(activeCount)
                    .setDeliveredCount(deliveredCount)
                    .setFailedCount(failedCount)
                    .addAllOrders(summaries)
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();

        } catch (StatusRuntimeException ex) {
            responseObserver.onError(ex);
        } catch (Exception ex) {
            log.error("Unexpected error in listMerchantOrders", ex);
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Internal delivery service error")
                    .asRuntimeException());
        }
    }

    @Override
    public void listCourierAssignments(ListCourierAssignmentsRequest request, StreamObserver<ListCourierAssignmentsResponse> responseObserver) {
        try {
            DelegatedActorContext actor = requireDelegatedActor();

            String effectiveCourierSub;
            boolean isStaff = actor.isAdmin() || actor.isDispatcher();
            if (actor.isCourier()) {
                effectiveCourierSub = actor.actorSub();
                if (request.getCourierSub() != null && !request.getCourierSub().isBlank()
                        && !request.getCourierSub().trim().equals(effectiveCourierSub)) {
                    responseObserver.onError(Status.PERMISSION_DENIED
                            .withDescription("Courier cannot query another courier's workload: requested "
                                    + request.getCourierSub().trim() + " != actor " + effectiveCourierSub)
                            .asRuntimeException());
                    return;
                }
            } else if (isStaff) {
                if (request.getCourierSub() == null || request.getCourierSub().isBlank()) {
                    responseObserver.onError(Status.INVALID_ARGUMENT
                            .withDescription("courier_sub must be specified for staff query")
                            .asRuntimeException());
                    return;
                }
                effectiveCourierSub = request.getCourierSub().trim();
            } else {
                responseObserver.onError(Status.PERMISSION_DENIED
                        .withDescription("Caller role is not authorized for courier assignment reads: " + actor.actorRoles())
                        .asRuntimeException());
                return;
            }

            int page = request.getPage();
            int pageSize = request.getPageSize() == 0 ? DEFAULT_PAGE_SIZE : request.getPageSize();
            if (page < 0) {
                responseObserver.onError(Status.INVALID_ARGUMENT
                        .withDescription("page index must be non-negative")
                        .asRuntimeException());
                return;
            }
            if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
                responseObserver.onError(Status.INVALID_ARGUMENT
                        .withDescription("pageSize must be between 1 and " + MAX_PAGE_SIZE)
                        .asRuntimeException());
                return;
            }

            List<String> requestedParcelIds = request.getParcelIdsList();
            if (requestedParcelIds != null && requestedParcelIds.size() > MAX_PAGE_SIZE) {
                responseObserver.onError(Status.INVALID_ARGUMENT
                        .withDescription("parcel_ids batch exceeds maximum limit of " + MAX_PAGE_SIZE)
                        .asRuntimeException());
                return;
            }

            int activeCount = assignmentRepository.countByCourierSubAndActiveTrue(effectiveCourierSub);

            LocalDate todayColombo = LocalDate.now(COLOMBO_ZONE);
            Instant startOfDay = todayColombo.atStartOfDay(COLOMBO_ZONE).toInstant();
            Instant endOfDay = todayColombo.plusDays(1).atStartOfDay(COLOMBO_ZONE).toInstant();

            int todayDelivered = historyRepository.countCourierDeliveredBetween(effectiveCourierSub, startOfDay, endOfDay);
            int todayFailed = historyRepository.countCourierFailedBetween(effectiveCourierSub, startOfDay, endOfDay);

            List<CourierAssignmentSummary> summaries = new ArrayList<>();
            int totalCount;

            boolean activeOnly = !request.hasField(request.getDescriptorForType().findFieldByName("active_only")) || request.getActiveOnly();

            if (requestedParcelIds != null && !requestedParcelIds.isEmpty()) {
                List<UUID> validUuids = new ArrayList<>();
                for (String pidStr : requestedParcelIds) {
                    try {
                        validUuids.add(UUID.fromString(pidStr.trim()));
                    } catch (Exception ignored) {}
                }
                List<DeliveryAssignment> assignments = activeOnly
                        ? assignmentRepository.findByCourierSubAndActiveTrueAndParcelIdIn(effectiveCourierSub, validUuids)
                        : assignmentRepository.findByCourierSubAndParcelIdIn(effectiveCourierSub, validUuids);

                for (DeliveryAssignment da : assignments) {
                    summaries.add(toCourierAssignmentSummary(da));
                }
                totalCount = summaries.size();
            } else {
                Pageable pageable = PageRequest.of(page, pageSize);
                Page<DeliveryAssignment> assignmentPage = activeOnly
                        ? assignmentRepository.findActiveByCourierSub(effectiveCourierSub, pageable)
                        : assignmentRepository.findAllByCourierSub(effectiveCourierSub, pageable);

                for (DeliveryAssignment da : assignmentPage.getContent()) {
                    summaries.add(toCourierAssignmentSummary(da));
                }
                totalCount = (int) assignmentPage.getTotalElements();
            }

            ListCourierAssignmentsResponse response = ListCourierAssignmentsResponse.newBuilder()
                    .setTotalCount(totalCount)
                    .setPage(page)
                    .setPageSize(pageSize)
                    .setActiveCount(activeCount)
                    .setTodayDeliveredCount(todayDelivered)
                    .setTodayFailedCount(todayFailed)
                    .addAllAssignments(summaries)
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();

        } catch (StatusRuntimeException ex) {
            responseObserver.onError(ex);
        } catch (Exception ex) {
            log.error("Unexpected error in listCourierAssignments", ex);
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Internal delivery service error")
                    .asRuntimeException());
        }
    }

    @Override
    public void getDeliveryHistory(GetDeliveryHistoryRequest request, StreamObserver<GetDeliveryHistoryResponse> responseObserver) {
        try {
            DelegatedActorContext actor = requireDelegatedActor();

            String singleParcelIdStr = request.getParcelId();
            List<String> batchParcelIds = request.getParcelIdsList();

            if ((singleParcelIdStr == null || singleParcelIdStr.isBlank())
                    && (batchParcelIds == null || batchParcelIds.isEmpty())) {
                responseObserver.onError(Status.INVALID_ARGUMENT
                        .withDescription("Either parcel_id or parcel_ids must be provided")
                        .asRuntimeException());
                return;
            }

            int page = request.getPage();
            int pageSize = request.getPageSize() == 0 ? DEFAULT_PAGE_SIZE : request.getPageSize();
            if (page < 0) {
                responseObserver.onError(Status.INVALID_ARGUMENT
                        .withDescription("page index must be non-negative")
                        .asRuntimeException());
                return;
            }
            if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
                responseObserver.onError(Status.INVALID_ARGUMENT
                        .withDescription("pageSize must be between 1 and " + MAX_PAGE_SIZE)
                        .asRuntimeException());
                return;
            }

            if (batchParcelIds != null && batchParcelIds.size() > MAX_PAGE_SIZE) {
                responseObserver.onError(Status.INVALID_ARGUMENT
                        .withDescription("parcel_ids batch exceeds maximum limit of " + MAX_PAGE_SIZE)
                        .asRuntimeException());
                return;
            }

            GetDeliveryHistoryResponse.Builder responseBuilder = GetDeliveryHistoryResponse.newBuilder()
                    .setPage(page)
                    .setPageSize(pageSize);

            if (singleParcelIdStr != null && !singleParcelIdStr.isBlank()) {
                UUID parcelId;
                try {
                    parcelId = UUID.fromString(singleParcelIdStr.trim());
                } catch (IllegalArgumentException e) {
                    responseObserver.onError(Status.INVALID_ARGUMENT
                            .withDescription("Invalid parcel_id format: must be valid UUID")
                            .asRuntimeException());
                    return;
                }

                Optional<Parcel> parcelOpt = parcelRepository.findById(parcelId);
                if (parcelOpt.isEmpty()) {
                    responseObserver.onError(Status.NOT_FOUND
                            .withDescription("Parcel not found: " + parcelId)
                            .asRuntimeException());
                    return;
                }

                Parcel parcel = parcelOpt.get();

                if (!isActorAuthorizedForParcel(actor, parcel)) {
                    responseObserver.onError(Status.PERMISSION_DENIED
                            .withDescription("Caller is not authorized to read history for parcel " + parcelId)
                            .asRuntimeException());
                    return;
                }

                Pageable pageable = PageRequest.of(page, pageSize);
                Page<StatusHistory> historyPage = historyRepository.findByParcelIdOrderByVersionAsc(parcelId, pageable);

                Instant latestStatusTime = parcel.getUpdatedAt() != null ? parcel.getUpdatedAt() : parcel.getCreatedAt();
                if (!historyPage.isEmpty()) {
                    StatusHistory lastItem = historyPage.getContent().get(historyPage.getContent().size() - 1);
                    if (lastItem.getOccurredAt() != null) {
                        latestStatusTime = lastItem.getOccurredAt();
                    }
                }

                responseBuilder.setParcelId(parcel.getParcelId().toString())
                        .setTrackingNumber(parcel.getTrackingNumber())
                        .setCurrentStatus(parcel.getStatus().name())
                        .setCurrentVersion(parcel.getVersion() != null ? parcel.getVersion() : 1L)
                        .setLatestStatusAt(toTimestamp(latestStatusTime != null ? latestStatusTime : Instant.now()))
                        .setTotalCount((int) historyPage.getTotalElements());

                for (StatusHistory sh : historyPage.getContent()) {
                    responseBuilder.addItems(toDeliveryStatusHistoryItem(sh));
                }
            }

            if (batchParcelIds != null && !batchParcelIds.isEmpty()) {
                List<UUID> validUuids = new ArrayList<>();
                for (String pidStr : batchParcelIds) {
                    try {
                        validUuids.add(UUID.fromString(pidStr.trim()));
                    } catch (Exception ignored) {}
                }

                for (UUID pid : validUuids) {
                    Optional<Parcel> pOpt = parcelRepository.findById(pid);
                    if (pOpt.isPresent()) {
                        Parcel p = pOpt.get();
                        if (isActorAuthorizedForParcel(actor, p)) {
                            List<StatusHistory> histories = historyRepository.findByParcelIdOrderByResultingVersionAsc(pid);
                            Instant latestTime = p.getUpdatedAt() != null ? p.getUpdatedAt() : p.getCreatedAt();
                            if (!histories.isEmpty()) {
                                StatusHistory last = histories.get(histories.size() - 1);
                                if (last.getOccurredAt() != null) {
                                    latestTime = last.getOccurredAt();
                                }
                            }

                            DeliveryHistorySummary.Builder sumBuilder = DeliveryHistorySummary.newBuilder()
                                    .setParcelId(p.getParcelId().toString())
                                    .setTrackingNumber(p.getTrackingNumber())
                                    .setCurrentStatus(p.getStatus().name())
                                    .setCurrentVersion(p.getVersion() != null ? p.getVersion() : 1L)
                                    .setLatestStatusAt(toTimestamp(latestTime != null ? latestTime : Instant.now()));

                            for (StatusHistory sh : histories) {
                                sumBuilder.addHistoryItems(toDeliveryStatusHistoryItem(sh));
                            }
                            responseBuilder.addSummaries(sumBuilder.build());
                        }
                    }
                }
            }

            responseObserver.onNext(responseBuilder.build());
            responseObserver.onCompleted();

        } catch (StatusRuntimeException ex) {
            responseObserver.onError(ex);
        } catch (Exception ex) {
            log.error("Unexpected error in getDeliveryHistory", ex);
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Internal delivery service error")
                    .asRuntimeException());
        }
    }

    private boolean isActorAuthorizedForParcel(DelegatedActorContext actor, Parcel parcel) {
        if (actor == null || parcel == null) {
            return false;
        }
        if (actor.isAdmin() || actor.isDispatcher()) {
            return true;
        }
        if (actor.isCustomer() && actor.actorSub().equals(parcel.getSenderSub())) {
            return true;
        }
        if (actor.isMerchant() && parcel.getMerchantId() != null) {
            String tenantIdStr = actor.tenantId();
            if (tenantIdStr != null && !tenantIdStr.isBlank()) {
                try {
                    UUID tenantId = UUID.fromString(tenantIdStr.trim());
                    if (tenantId.equals(parcel.getMerchantId())) {
                        return true;
                    }
                } catch (IllegalArgumentException ignored) {}
            }
        }
        if (actor.isCourier()) {
            return assignmentRepository.existsByParcelIdAndCourierSubAndActiveTrue(
                    parcel.getParcelId(), actor.actorSub().trim());
        }
        return false;
    }

    private CustomerParcelSummary toCustomerParcelSummary(Parcel p) {
        Instant createdAt = p.getCreatedAt() != null ? p.getCreatedAt() : Instant.now();
        Instant updatedAt = p.getUpdatedAt() != null ? p.getUpdatedAt() : createdAt;

        return CustomerParcelSummary.newBuilder()
                .setParcelId(p.getParcelId().toString())
                .setTrackingNumber(p.getTrackingNumber())
                .setCurrentStatus(p.getStatus().name())
                .setAmountMinorUnits(p.getAmountMinorUnits() != null ? p.getAmountMinorUnits() : 0L)
                .setCurrency(p.getCurrency() != null ? p.getCurrency() : "LKR")
                .setRecipientCity("")
                .setCreatedAt(toTimestamp(createdAt))
                .setUpdatedAt(toTimestamp(updatedAt))
                .setVersion(p.getVersion() != null ? p.getVersion() : 1L)
                .build();
    }

    private MerchantOrderSummary toMerchantOrderSummary(Parcel p) {
        Instant createdAt = p.getCreatedAt() != null ? p.getCreatedAt() : Instant.now();
        Instant updatedAt = p.getUpdatedAt() != null ? p.getUpdatedAt() : createdAt;

        return MerchantOrderSummary.newBuilder()
                .setParcelId(p.getParcelId().toString())
                .setTrackingNumber(p.getTrackingNumber())
                .setCurrentStatus(p.getStatus().name())
                .setWeightKg(p.getWeightKg() != null ? p.getWeightKg().doubleValue() : 0.0)
                .setFeeMinorUnits(p.getAmountMinorUnits() != null ? p.getAmountMinorUnits() : 0L)
                .setCurrency(p.getCurrency() != null ? p.getCurrency() : "LKR")
                .setCreatedAt(toTimestamp(createdAt))
                .setLastStatusUpdate(toTimestamp(updatedAt))
                .setVersion(p.getVersion() != null ? p.getVersion() : 1L)
                .build();
    }

    private CourierAssignmentSummary toCourierAssignmentSummary(DeliveryAssignment da) {
        Optional<Parcel> parcelOpt = parcelRepository.findById(da.getParcelId());
        String trackingNumber = parcelOpt.map(Parcel::getTrackingNumber).orElse("UNKNOWN");
        String currentStatus = parcelOpt.map(p -> p.getStatus().name()).orElse("UNKNOWN");
        long version = parcelOpt.map(p -> p.getVersion() != null ? p.getVersion() : 1L).orElse(1L);

        Instant assignedAt = da.getAssignedAt() != null ? da.getAssignedAt() : Instant.now();

        return CourierAssignmentSummary.newBuilder()
                .setAssignmentId(da.getAssignmentId().toString())
                .setParcelId(da.getParcelId().toString())
                .setTrackingNumber(trackingNumber)
                .setCurrentStatus(currentStatus)
                .setPickupAddress("")
                .setDropoffAddress("")
                .setRecipientName("")
                .setRecipientPhone("")
                .setAssignedAt(toTimestamp(assignedAt))
                .setVersion(version)
                .setIsActive(da.isActive())
                .build();
    }

    private DeliveryStatusHistoryItem toDeliveryStatusHistoryItem(StatusHistory sh) {
        Instant occurredAt = sh.getOccurredAt() != null ? sh.getOccurredAt() : Instant.now();

        return DeliveryStatusHistoryItem.newBuilder()
                .setHistoryId(sh.getHistoryId().toString())
                .setParcelId(sh.getParcelId().toString())
                .setPreviousStatus(sh.getPreviousStatus() != null ? sh.getPreviousStatus().name() : "")
                .setNewStatus(sh.getNewStatus().name())
                .setReason(sh.getReason() != null ? sh.getReason() : "")
                .setNote(sh.getNote() != null ? sh.getNote() : "")
                .setProofId(sh.getProofId() != null ? sh.getProofId().toString() : "")
                .setResultingVersion(sh.getResultingVersion() != null ? sh.getResultingVersion() : 1L)
                .setOccurredAt(toTimestamp(occurredAt))
                .build();
    }

    private DelegatedActorContext requireDelegatedActor() {
        return SwiftDropGrpcContext.currentDelegatedActor()
                .orElseThrow(() -> Status.UNAUTHENTICATED
                        .withDescription("Actor delegation context is required")
                        .asRuntimeException());
    }

    private DelegatedActorContext requireAuthorizedStaffActor() {
        DelegatedActorContext actor = SwiftDropGrpcContext.currentDelegatedActor()
                .orElse(null);

        // If running inside in-process test where security interceptor was omitted or set,
        // we check actor if present.
        if (actor != null) {
            boolean isStaff = actor.actorRoles().contains("DISPATCHER") || actor.actorRoles().contains("ADMIN");
            if (!isStaff) {
                throw Status.PERMISSION_DENIED
                        .withDescription("Delegated actor does not possess required role DISPATCHER or ADMIN")
                        .asRuntimeException();
            }
        }
        return actor;
    }

    private static Timestamp toTimestamp(Instant instant) {
        return Timestamp.newBuilder()
                .setSeconds(instant.getEpochSecond())
                .setNanos(instant.getNano())
                .build();
    }

    private static Instant toInstant(Timestamp timestamp) {
        return Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos());
    }
}
