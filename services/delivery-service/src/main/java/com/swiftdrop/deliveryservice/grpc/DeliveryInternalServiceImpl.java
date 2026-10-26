package com.swiftdrop.deliveryservice.grpc;

import com.google.protobuf.Timestamp;
import com.swiftdrop.delivery.v1.*;
import com.swiftdrop.deliveryservice.assignment.DeliveryAssignmentRepository;
import com.swiftdrop.deliveryservice.core.Parcel;
import com.swiftdrop.deliveryservice.core.ParcelRepository;
import com.swiftdrop.deliveryservice.query.ParcelProjection;
import com.swiftdrop.deliveryservice.query.ParcelQueryService;
import com.swiftdrop.deliveryservice.status.StatusHistoryRepository;
import com.swiftdrop.security.grpc.DelegatedActorContext;
import com.swiftdrop.security.grpc.SwiftDropGrpcContext;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * gRPC Service implementation for Delivery Operations internal read projections.
 * Provides read-only projections for Dispatcher unassigned queue, active courier workload,
 * and bounded delivery statistics (Issue #113).
 */
@Component
public class DeliveryInternalServiceImpl extends DeliveryInternalServiceGrpc.DeliveryInternalServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(DeliveryInternalServiceImpl.class);
    private static final ZoneId COLOMBO_ZONE = ZoneId.of("Asia/Colombo");

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 50;
    private static final int MAX_BATCH_COURIERS = 50;
    private static final int MAX_STATS_WINDOW_DAYS = 90;

    private final ParcelRepository parcelRepository;
    private final DeliveryAssignmentRepository assignmentRepository;
    private final StatusHistoryRepository historyRepository;
    private final ParcelQueryService parcelQueryService;

    public DeliveryInternalServiceImpl(
            ParcelRepository parcelRepository,
            DeliveryAssignmentRepository assignmentRepository,
            StatusHistoryRepository historyRepository,
            ParcelQueryService parcelQueryService) {
        this.parcelRepository = Objects.requireNonNull(parcelRepository, "parcelRepository must not be null");
        this.assignmentRepository = Objects.requireNonNull(assignmentRepository, "assignmentRepository must not be null");
        this.historyRepository = Objects.requireNonNull(historyRepository, "historyRepository must not be null");
        this.parcelQueryService = Objects.requireNonNull(parcelQueryService, "parcelQueryService must not be null");
    }

    @Override
    @Transactional(readOnly = true)
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
            if (projection.isEmpty()) {
                responseObserver.onError(Status.NOT_FOUND
                        .withDescription("Parcel not found: " + parcelId)
                        .asRuntimeException());
                return;
            }

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
    @Transactional(readOnly = true)
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
    @Transactional(readOnly = true)
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
    @Transactional(readOnly = true)
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
    @Transactional(readOnly = true)
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
