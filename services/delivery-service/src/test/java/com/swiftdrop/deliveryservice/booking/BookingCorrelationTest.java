package com.swiftdrop.deliveryservice.booking;

import com.swiftdrop.deliveryservice.core.ParcelStatus;
import com.swiftdrop.deliveryservice.query.dto.ParcelDetailResponse;
import com.swiftdrop.deliveryservice.quote.BookingAuthorization;
import com.swiftdrop.deliveryservice.quote.BookingQuoteProvider;
import com.swiftdrop.deliveryservice.quote.QuoteValidation;
import com.swiftdrop.security.correlation.CorrelationContext;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class BookingCorrelationTest {
    static ValidatorFactory validatorFactory;
    private final BookingRepository repository = mock(BookingRepository.class);
    private final BookingQuoteProvider quotes = mock(BookingQuoteProvider.class);
    private final BookingAuthorization authorization = mock(BookingAuthorization.class);
    private final JsonMapper json = JsonMapper.builder().build();
    private final Instant now = Instant.parse("2026-10-28T00:00:00Z");
    private final UUID quoteId = UUID.randomUUID();
    private final UUID idempotencyKey = UUID.randomUUID();
    private final BookingActor actor = new BookingActor("customer-test-sub");

    private BookingService service;

    @BeforeAll
    static void init() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
    }

    @AfterAll
    static void destroy() {
        validatorFactory.close();
    }

    @BeforeEach
    void setup() {
        CorrelationContext.clear();
        var txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        service = new BookingService(
                repository,
                quotes,
                authorization,
                json,
                Clock.fixed(now, ZoneOffset.UTC),
                validatorFactory.getValidator(),
                txManager
        );
        when(repository.replay(anyString(), any())).thenReturn(Optional.empty());
        when(repository.insert(any(), any(), anyString())).thenReturn(true);
        when(quotes.validate(eq(quoteId), eq(actor.subject()))).thenReturn(
                new QuoteValidation(true, quoteId, "RATE-V1", 45000, "LKR", now.plusSeconds(300), "VALID")
        );
    }

    private BookParcelRequest sampleRequest() {
        return new BookParcelRequest(
                quoteId,
                "No. 12, Galle Road, Colombo",
                "No. 45, Kandy Road, Kadawatha",
                "Recipient Name",
                "+94771234567",
                "Handle with care"
        );
    }

    @Test
    void booking_withCorrelationContext_populatesEventCorrelationId() throws Exception {
        String testCorrelationId = "corr-test-booking-abc-123";
        CorrelationContext.set(testCorrelationId);

        try {
            var result = service.book(actor, idempotencyKey, sampleRequest());
            assertThat(result).isNotNull();

            ArgumentCaptor<String> eventCaptor = ArgumentCaptor.forClass(String.class);
            verify(repository).finish(eq(result), eq(idempotencyKey), anyString(), any(UUID.class), eventCaptor.capture());

            String eventJson = eventCaptor.getValue();
            Map<?, ?> eventMap = json.readValue(eventJson, Map.class);
            assertThat(eventMap.get("correlationId")).isEqualTo(testCorrelationId);
        } finally {
            CorrelationContext.clear();
        }
    }

    @Test
    void booking_withoutCorrelationContext_fallsBackToIdempotencyKey() throws Exception {
        CorrelationContext.clear();

        var result = service.book(actor, idempotencyKey, sampleRequest());
        assertThat(result).isNotNull();

        ArgumentCaptor<String> eventCaptor = ArgumentCaptor.forClass(String.class);
        verify(repository).finish(eq(result), eq(idempotencyKey), anyString(), any(UUID.class), eventCaptor.capture());

        String eventJson = eventCaptor.getValue();
        Map<?, ?> eventMap = json.readValue(eventJson, Map.class);
        assertThat(eventMap.get("correlationId")).isEqualTo(idempotencyKey.toString());
    }

    @Test
    void booking_idempotentReplay_returnsExistingResultWithoutRecreatingEvent() {
        var existingBooking = new ParcelBooking(
                UUID.randomUUID(),
                "SWD-20261028-TEST1234",
                actor.subject(),
                quoteId,
                "CREATED",
                1L,
                45000,
                "LKR",
                "RATE-V1",
                "No. 12, Galle Road",
                "No. 45, Kandy Road",
                now
        );

        String digest;
        try {
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(json.writeValueAsString(sampleRequest()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            digest = java.util.HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        var replayRecord = new BookingRepository.Replay(digest, existingBooking);

        when(repository.replay(eq(actor.subject()), eq(idempotencyKey))).thenReturn(Optional.of(replayRecord));

        var result = service.book(actor, idempotencyKey, sampleRequest());

        assertThat(result).isEqualTo(existingBooking);
        verify(repository, never()).insert(any(), any(), any());
        verify(repository, never()).finish(any(), any(), any(), any(), any());
        verifyNoInteractions(quotes);
    }

    @Test
    @DisplayName("Merchant lost response replay returns the cached canonical booking without re-evaluating quote")
    void merchantLostResponseReplayReturnsCachedCanonicalBooking() {
        UUID merchantId = UUID.randomUUID();
        String merchantSub = "merchant-user-123";
        MerchantBookParcelRequest merchantReq = new MerchantBookParcelRequest(
                quoteId,
                "No. 12, Galle Road",
                "No. 45, Kandy Road",
                "Kasun Perera",
                "+94771234567",
                "REF-MERCH-001",
                "Leave at front desk"
        );

        ParcelDetailResponse existingResponse = new ParcelDetailResponse(
                UUID.randomUUID(),
                "SWD-20261028-ABC12345",
                merchantSub,
                merchantId,
                ParcelStatus.CREATED,
                1L,
                2.50,
                "STANDARD",
                45000L,
                "LKR",
                "RATE-V1",
                "No. 12, Galle Road",
                "No. 45, Kandy Road",
                null,
                now
        );

        String digest;
        try {
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(json.writeValueAsString(merchantReq).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            digest = java.util.HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        var replayRecord = new BookingRepository.Replay(digest, null, json.writeValueAsString(existingResponse));
        when(repository.replayMerchant(eq(merchantId), eq(idempotencyKey))).thenReturn(Optional.of(replayRecord));

        var result = service.bookMerchant(merchantId, merchantSub, idempotencyKey, merchantReq);

        assertThat(result).isEqualTo(existingResponse);
        verify(repository, never()).insertMerchantParcel(any(), any(), any(), any(), any(), any(), anyLong(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(repository, never()).finishMerchant(any(), any(), any(), any(), any(), any(), any(), any(), any());
        verifyNoInteractions(quotes);
    }
}
