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
