package com.swiftdrop.deliveryservice.quote;

import com.google.protobuf.Timestamp;
import com.swiftdrop.pricing.v1.PricingInternalServiceGrpc;
import com.swiftdrop.pricing.v1.ValidateQuoteRequest;
import com.swiftdrop.pricing.v1.ValidateQuoteResponse;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class GrpcBookingQuoteProviderTest {

    private PricingInternalServiceGrpc.PricingInternalServiceBlockingStub pricingStub;
    private GrpcBookingQuoteProvider provider;

    private final UUID quoteId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final String customerSub = "cust-alice-123";

    @BeforeEach
    void setUp() {
        pricingStub = mock(PricingInternalServiceGrpc.PricingInternalServiceBlockingStub.class);
        when(pricingStub.withDeadlineAfter(anyLong(), any(TimeUnit.class))).thenReturn(pricingStub);
        provider = new GrpcBookingQuoteProvider(pricingStub);
    }

    @Test
    @DisplayName("Successfully validates quote and maps authoritative snapshot values")
    void validate_successfulQuote() {
        Instant expires = Instant.parse("2026-10-28T12:00:00Z");
        ValidateQuoteResponse response = ValidateQuoteResponse.newBuilder()
                .setIsValid(true)
                .setQuoteId(quoteId.toString())
                .setRateVersion("RATE-2026-V1")
                .setVerifiedAmountMinorUnits(45000L)
                .setCurrency("LKR")
                .setExpiresAt(Timestamp.newBuilder()
                        .setSeconds(expires.getEpochSecond())
                        .setNanos(expires.getNano())
                        .build())
                .setValidationCode("VALID")
                .build();

        when(pricingStub.validateQuote(any(ValidateQuoteRequest.class))).thenReturn(response);

        QuoteValidation validation = provider.validate(quoteId, customerSub);

        assertThat(validation.isValid()).isTrue();
        assertThat(validation.quoteId()).isEqualTo(quoteId);
        assertThat(validation.rateVersion()).isEqualTo("RATE-2026-V1");
        assertThat(validation.verifiedAmountMinorUnits()).isEqualTo(45000L);
        assertThat(validation.currency()).isEqualTo("LKR");
        assertThat(validation.expiresAt()).isEqualTo(expires);
        assertThat(validation.validationCode()).isEqualTo("VALID");

        ArgumentCaptor<ValidateQuoteRequest> captor = ArgumentCaptor.forClass(ValidateQuoteRequest.class);
        verify(pricingStub).validateQuote(captor.capture());
        assertThat(captor.getValue().getQuoteId()).isEqualTo(quoteId.toString());
    }

    @Test
    @DisplayName("Handles expired quote response safely without throwing exception")
    void validate_expiredQuote() {
        ValidateQuoteResponse response = ValidateQuoteResponse.newBuilder()
                .setIsValid(false)
                .setQuoteId(quoteId.toString())
                .setRateVersion("RATE-2026-V1")
                .setVerifiedAmountMinorUnits(45000L)
                .setCurrency("LKR")
                .setValidationCode("QUOTE_EXPIRED")
                .build();

        when(pricingStub.validateQuote(any(ValidateQuoteRequest.class))).thenReturn(response);

        QuoteValidation validation = provider.validate(quoteId, customerSub);

        assertThat(validation.isValid()).isFalse();
        assertThat(validation.validationCode()).isEqualTo("QUOTE_EXPIRED");
    }

    @Test
    @DisplayName("Handles non-existent quote response safely")
    void validate_notFoundQuote() {
        ValidateQuoteResponse response = ValidateQuoteResponse.newBuilder()
                .setIsValid(false)
                .setQuoteId(quoteId.toString())
                .setValidationCode("QUOTE_NOT_FOUND")
                .build();

        when(pricingStub.validateQuote(any(ValidateQuoteRequest.class))).thenReturn(response);

        QuoteValidation validation = provider.validate(quoteId, customerSub);

        assertThat(validation.isValid()).isFalse();
        assertThat(validation.validationCode()).isEqualTo("QUOTE_NOT_FOUND");
    }

    @Test
    @DisplayName("Fails closed on gRPC PERMISSION_DENIED (unauthorized actor or tenant)")
    void validate_permissionDeniedFailsClosed() {
        when(pricingStub.validateQuote(any(ValidateQuoteRequest.class)))
                .thenThrow(new StatusRuntimeException(Status.PERMISSION_DENIED.withDescription("Not authorized")));

        QuoteValidation validation = provider.validate(quoteId, customerSub);

        assertThat(validation.isValid()).isFalse();
        assertThat(validation.validationCode()).isEqualTo("PERMISSION_DENIED");
        assertThat(validation.verifiedAmountMinorUnits()).isEqualTo(0L);
    }

    @Test
    @DisplayName("Fails closed on gRPC DEADLINE_EXCEEDED (timeout)")
    void validate_deadlineExceededFailsClosed() {
        when(pricingStub.validateQuote(any(ValidateQuoteRequest.class)))
                .thenThrow(new StatusRuntimeException(Status.DEADLINE_EXCEEDED.withDescription("Timeout")));

        QuoteValidation validation = provider.validate(quoteId, customerSub);

        assertThat(validation.isValid()).isFalse();
        assertThat(validation.validationCode()).isEqualTo("DEADLINE_EXCEEDED");
    }

    @Test
    @DisplayName("Fails closed on gRPC UNAVAILABLE without accepting a price snapshot")
    void validate_unavailableFailsClosed() {
        when(pricingStub.validateQuote(any(ValidateQuoteRequest.class)))
                .thenThrow(new StatusRuntimeException(Status.UNAVAILABLE.withDescription("Provider unavailable")));

        QuoteValidation validation = provider.validate(quoteId, customerSub);

        assertThat(validation.isValid()).isFalse();
        assertThat(validation.validationCode()).isEqualTo("UNAVAILABLE");
        assertThat(validation.verifiedAmountMinorUnits()).isZero();
        assertThat(validation.rateVersion()).isNull();
    }
}
