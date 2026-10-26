package com.swiftdrop.communicationsservice.client;

import com.swiftdrop.delivery.v1.AuthorizeEventRecipientRequest;
import com.swiftdrop.delivery.v1.AuthorizeEventRecipientResponse;
import com.swiftdrop.delivery.v1.DeliveryInternalServiceGrpc;
import io.grpc.Channel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Production gRPC adapter implementing DeliveryRecipientAuthorizationClient (IF-GRPC-11, #111).
 * Queries DeliveryInternalService.AuthorizeEventRecipient using authenticated machine credentials.
 * Translates gRPC transport errors to retryable or non-retryable domain exceptions.
 */
public class GrpcDeliveryRecipientAuthorizationClient implements DeliveryRecipientAuthorizationClient {

    private static final Logger log = LoggerFactory.getLogger(GrpcDeliveryRecipientAuthorizationClient.class);
    public static final long DEFAULT_DEADLINE_MS = 2000L;

    private final DeliveryInternalServiceGrpc.DeliveryInternalServiceBlockingStub blockingStub;
    private final long deadlineMs;

    public GrpcDeliveryRecipientAuthorizationClient(Channel channel, long deadlineMs) {
        Objects.requireNonNull(channel, "gRPC channel is required");
        this.blockingStub = DeliveryInternalServiceGrpc.newBlockingStub(channel);
        this.deadlineMs = deadlineMs > 0 ? deadlineMs : DEFAULT_DEADLINE_MS;
    }

    public GrpcDeliveryRecipientAuthorizationClient(DeliveryInternalServiceGrpc.DeliveryInternalServiceBlockingStub stub, long deadlineMs) {
        this.blockingStub = Objects.requireNonNull(stub, "blockingStub is required");
        this.deadlineMs = deadlineMs > 0 ? deadlineMs : DEFAULT_DEADLINE_MS;
    }

    public GrpcDeliveryRecipientAuthorizationClient(Channel channel) {
        this(channel, DEFAULT_DEADLINE_MS);
    }

    @Override
    public DeliveryEventAuthorization authorizeEventRecipient(
            UUID eventId,
            UUID parcelId,
            String candidateRecipientSub,
            UUID candidateMerchantId,
            boolean allowStale) {

        if (eventId == null) {
            throw new IllegalArgumentException("eventId is required");
        }
        if (parcelId == null) {
            throw new IllegalArgumentException("parcelId is required");
        }

        log.debug("Authorizing delivery event [eventId={}, parcelId={}, candidateRecipientSub={}, candidateMerchantId={}]",
                eventId, parcelId, candidateRecipientSub, candidateMerchantId);

        AuthorizeEventRecipientRequest.Builder builder = AuthorizeEventRecipientRequest.newBuilder()
                .setEventId(eventId.toString())
                .setParcelId(parcelId.toString())
                .setAllowStale(allowStale);

        if (candidateRecipientSub != null && !candidateRecipientSub.isBlank()) {
            builder.setRecipientSub(candidateRecipientSub.trim());
        }
        if (candidateMerchantId != null) {
            builder.setMerchantId(candidateMerchantId.toString());
        }

        try {
            AuthorizeEventRecipientResponse response = blockingStub
                    .withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS)
                    .authorizeEventRecipient(builder.build());

            log.info("Received delivery event authorization decision [eventId={}, parcelId={}, isAuthorized={}, reason={}]",
                    eventId, parcelId, response.getIsAuthorized(), response.getAuthorizationReason());

            UUID resolvedParcelId = UUID.fromString(response.getParcelId());
            UUID resolvedEventId = UUID.fromString(response.getEventId());
            UUID resolvedMerchantId = (response.getMerchantId() != null && !response.getMerchantId().isBlank())
                    ? UUID.fromString(response.getMerchantId())
                    : null;

            return new DeliveryEventAuthorization(
                    response.getIsAuthorized(),
                    resolvedParcelId,
                    resolvedEventId,
                    response.getRecipientSub(),
                    resolvedMerchantId,
                    response.getTrackingNumber(),
                    response.getCurrentStatus(),
                    response.getEventType(),
                    response.getEventVersion(),
                    response.getCurrentVersion(),
                    response.getIsTerminal(),
                    response.getIsStale(),
                    response.getAuthorizationReason()
            );

        } catch (StatusRuntimeException ex) {
            Status.Code code = ex.getStatus().getCode();
            String description = ex.getStatus().getDescription();

            if (code == Status.Code.DEADLINE_EXCEEDED || code == Status.Code.UNAVAILABLE) {
                log.error("Delivery service unavailable or deadline exceeded [eventId={}, parcelId={}, code={}]",
                        eventId, parcelId, code);
                throw new DeliveryServiceUnavailableException(
                        "Delivery service unavailable during event recipient authorization (" + code + "): " + description, ex);
            }

            if (code == Status.Code.PERMISSION_DENIED || code == Status.Code.UNAUTHENTICATED) {
                log.error("Service caller authorization rejected by Delivery Operations [code={}, desc={}]",
                        code, description);
                throw new DeliverySecurityException(
                        "Delivery event recipient authorization rejected (" + code + "): " + description, ex);
            }

            if (code == Status.Code.INVALID_ARGUMENT) {
                log.warn("Invalid event recipient authorization request [desc={}]", description);
                throw new IllegalArgumentException("Invalid authorization request: " + description, ex);
            }

            log.error("Unexpected gRPC error during event recipient authorization [code={}]", code);
            throw new DeliveryAuthorizationException("Delivery event recipient authorization failed: " + description, ex);
        }
    }
}
