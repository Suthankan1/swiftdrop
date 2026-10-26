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
import com.swiftdrop.security.grpc.SwiftDropGrpcContext;
import com.swiftdrop.security.service.SwiftDropServiceIdentity;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Authoritative gRPC server implementation for DeliveryInternalService.
 *
 * Implements:
 * - IF-GRPC-05: GetParcelInternal for internal projections and GraphQL resolvers.
 * - GetCourierWorkload: Active workload calculation for couriers.
 * - IF-GRPC-11 (Issue #111 / N10-B07): AuthorizeEventRecipient for Communications notification workers.
 *
 * Enforces fail-closed security, cross-tenant isolation, anti-spoofing,
 * zero PII exposure, and explicit stale/terminal event evaluation.
 */
@Service
@Transactional(readOnly = true)
public class DeliveryInternalServiceImpl extends DeliveryInternalServiceGrpc.DeliveryInternalServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(DeliveryInternalServiceImpl.class);

    private final ParcelRepository parcelRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final DeliveryAssignmentRepository deliveryAssignmentRepository;

    public DeliveryInternalServiceImpl(ParcelRepository parcelRepository,
                                       OutboxEventRepository outboxEventRepository,
                                       DeliveryAssignmentRepository deliveryAssignmentRepository) {
        this.parcelRepository = Objects.requireNonNull(parcelRepository, "ParcelRepository is required");
        this.outboxEventRepository = Objects.requireNonNull(outboxEventRepository, "OutboxEventRepository is required");
        this.deliveryAssignmentRepository = Objects.requireNonNull(deliveryAssignmentRepository, "DeliveryAssignmentRepository is required");
    }

    @Override
    public void getParcelInternal(GetParcelInternalRequest request,
                                  StreamObserver<GetParcelInternalResponse> responseObserver) {
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
                    .withDescription("Invalid parcel_id format").asRuntimeException());
            return;
        }

        Optional<Parcel> parcelOpt = parcelRepository.findById(parcelId);
        if (parcelOpt.isEmpty()) {
            responseObserver.onError(Status.NOT_FOUND
                    .withDescription("Parcel not found: " + parcelId).asRuntimeException());
            return;
        }

        Parcel p = parcelOpt.get();
        String activeCourierSub = deliveryAssignmentRepository
                .findByParcelIdAndActiveTrue(parcelId)
                .map(DeliveryAssignment::getCourierSub)
                .orElse("");

        Instant createdAt = p.getCreatedAt() != null ? p.getCreatedAt() : Instant.now();
        Timestamp ts = Timestamp.newBuilder()
                .setSeconds(createdAt.getEpochSecond())
                .setNanos(createdAt.getNano())
                .build();

        GetParcelInternalResponse response = GetParcelInternalResponse.newBuilder()
                .setParcelId(p.getParcelId().toString())
                .setTrackingNumber(p.getTrackingNumber())
                .setCurrentStatus(p.getStatus().name())
                .setVersion(p.getVersion() != null ? p.getVersion() : 1L)
                .setAmountMinorUnits(p.getAmountMinorUnits() != null ? p.getAmountMinorUnits() : 0L)
                .setCurrency(p.getCurrency() != null ? p.getCurrency() : "LKR")
                .setCreatedBySub(p.getSenderSub())
                .setMerchantId(p.getMerchantId() != null ? p.getMerchantId().toString() : "")
                .setActiveCourierSub(activeCourierSub)
                .setCreatedAt(ts)
                .build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void getCourierWorkload(CourierWorkloadRequest request,
                                   StreamObserver<CourierWorkloadResponse> responseObserver) {
        String courierSub = request.getCourierSub();
        if (courierSub == null || courierSub.isBlank()) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("courier_sub is required").asRuntimeException());
            return;
        }

        int activeCount = deliveryAssignmentRepository.countByCourierSubAndActiveTrue(courierSub.trim());
        CourierWorkloadResponse response = CourierWorkloadResponse.newBuilder()
                .setCourierSub(courierSub.trim())
                .setActiveAssignmentsCount(activeCount)
                .build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
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
        Optional<String> activeCourierSub = deliveryAssignmentRepository
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
}
