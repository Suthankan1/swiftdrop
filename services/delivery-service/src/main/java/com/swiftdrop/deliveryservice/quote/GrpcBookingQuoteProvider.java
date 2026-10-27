package com.swiftdrop.deliveryservice.quote;

import com.swiftdrop.pricing.v1.PricingInternalServiceGrpc;
import com.swiftdrop.pricing.v1.ValidateQuoteRequest;
import com.swiftdrop.pricing.v1.ValidateQuoteResponse;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.swiftdrop.security.grpc.DelegatedActorContext;
import com.swiftdrop.security.grpc.SwiftDropGrpcContext;
import com.swiftdrop.security.jwt.SwiftDropRoles;
import com.swiftdrop.security.service.SwiftDropServiceIdentity;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * gRPC adapter implementing BookingQuoteProvider.
 * Invokes PricingInternalService.ValidateQuote over authenticated internal gRPC with a bounded deadline.
 *
 * Enforces:
 * - Authoritative quote resolution from Pricing (no local price recalculation, no client amounts trusted)
 * - Safe fail-closed semantics on gRPC error, timeout, or quote rejection
 * - Retry safety (read-only verification without reservation side-effects)
 */
public class GrpcBookingQuoteProvider implements BookingQuoteProvider {

    private static final Logger log = LoggerFactory.getLogger(GrpcBookingQuoteProvider.class);
    private static final long DEFAULT_DEADLINE_MS = 5000L;

    private final PricingInternalServiceGrpc.PricingInternalServiceBlockingStub pricingStub;
    private final long deadlineMs;

    public GrpcBookingQuoteProvider(PricingInternalServiceGrpc.PricingInternalServiceBlockingStub pricingStub, long deadlineMs) {
        this.pricingStub = Objects.requireNonNull(pricingStub, "pricingStub must not be null");
        this.deadlineMs = deadlineMs > 0 ? deadlineMs : DEFAULT_DEADLINE_MS;
    }

    public GrpcBookingQuoteProvider(PricingInternalServiceGrpc.PricingInternalServiceBlockingStub pricingStub) {
        this(pricingStub, DEFAULT_DEADLINE_MS);
    }

    @Override
    public QuoteValidation validateMerchantQuote(UUID quoteId, UUID merchantId, String authenticatedSubject) {
        Objects.requireNonNull(quoteId, "quoteId must not be null");
        Objects.requireNonNull(merchantId, "merchantId must not be null");

        String actorSub = authenticatedSubject != null && !authenticatedSubject.isBlank()
                ? authenticatedSubject
                : "merchant:" + merchantId;

        DelegatedActorContext actor = new DelegatedActorContext(
                actorSub,
                Set.of(SwiftDropRoles.ROLE_MERCHANT),
                SwiftDropServiceIdentity.DELIVERY_SERVICE,
                merchantId.toString()
        );

        try {
            return io.grpc.Context.current()
                    .withValue(SwiftDropGrpcContext.KEY_DELEGATED_ACTOR, actor)
                    .call(() -> validate(quoteId, actorSub));
        } catch (Exception e) {
            if (e instanceof RuntimeException re) throw re;
            throw new IllegalStateException("Failed to validate merchant quote via gRPC", e);
        }
    }

    @Override
    public QuoteValidation validate(UUID quoteId, String authenticatedSubject) {
        Objects.requireNonNull(quoteId, "quoteId must not be null");

        ValidateQuoteRequest request = ValidateQuoteRequest.newBuilder()
                .setQuoteId(quoteId.toString())
                .build();

        try {
            ValidateQuoteResponse response = pricingStub
                    .withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS)
                    .validateQuote(request);

            Instant expiresAt = null;
            if (response.hasExpiresAt() && response.getExpiresAt().getSeconds() > 0) {
                expiresAt = Instant.ofEpochSecond(
                        response.getExpiresAt().getSeconds(),
                        response.getExpiresAt().getNanos()
                );
            }

            UUID responseQuoteId;
            try {
                responseQuoteId = (response.getQuoteId() != null && !response.getQuoteId().isBlank())
                        ? UUID.fromString(response.getQuoteId())
                        : quoteId;
            } catch (IllegalArgumentException e) {
                responseQuoteId = quoteId;
            }

            return new QuoteValidation(
                    response.getIsValid(),
                    responseQuoteId,
                    response.getRateVersion(),
                    response.getVerifiedAmountMinorUnits(),
                    response.getCurrency(),
                    expiresAt,
                    response.getValidationCode()
            );

        } catch (StatusRuntimeException ex) {
            log.warn("Pricing quote verification gRPC failed for quote {}: {}", quoteId, ex.getStatus());
            return new QuoteValidation(
                    false,
                    quoteId,
                    null,
                    0L,
                    null,
                    null,
                    ex.getStatus().getCode().name()
            );
        } catch (Exception ex) {
            log.error("Unexpected error validating quote {} via gRPC", quoteId, ex);
            return new QuoteValidation(
                    false,
                    quoteId,
                    null,
                    0L,
                    null,
                    null,
                    "INTERNAL_ERROR"
            );
        }
    }
}
