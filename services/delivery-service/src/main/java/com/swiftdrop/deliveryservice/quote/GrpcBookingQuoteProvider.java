package com.swiftdrop.deliveryservice.quote;

import com.swiftdrop.pricing.v1.PricingInternalServiceGrpc;
import com.swiftdrop.pricing.v1.ValidateQuoteRequest;
import com.swiftdrop.pricing.v1.ValidateQuoteResponse;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Production gRPC adapter implementing BookingQuoteProvider.
 * Invokes PricingInternalService.ValidateQuote over authenticated internal gRPC with a bounded deadline.
 *
 * Enforces:
 * - Authoritative quote resolution from Pricing (no local price recalculation, no client amounts trusted)
 * - Safe fail-closed semantics on gRPC error, timeout, or quote rejection
 * - Retry safety (read-only verification without reservation side-effects)
 */
@Component
public class GrpcBookingQuoteProvider implements BookingQuoteProvider {

    private static final Logger log = LoggerFactory.getLogger(GrpcBookingQuoteProvider.class);
    private static final long DEFAULT_DEADLINE_MS = 2000L;

    private final PricingInternalServiceGrpc.PricingInternalServiceBlockingStub pricingStub;

    public GrpcBookingQuoteProvider(PricingInternalServiceGrpc.PricingInternalServiceBlockingStub pricingStub) {
        this.pricingStub = Objects.requireNonNull(pricingStub, "pricingStub must not be null");
    }

    @Override
    public QuoteValidation validate(UUID quoteId, String authenticatedSubject) {
        Objects.requireNonNull(quoteId, "quoteId must not be null");

        ValidateQuoteRequest request = ValidateQuoteRequest.newBuilder()
                .setQuoteId(quoteId.toString())
                .build();

        try {
            ValidateQuoteResponse response = pricingStub
                    .withDeadlineAfter(DEFAULT_DEADLINE_MS, TimeUnit.MILLISECONDS)
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
