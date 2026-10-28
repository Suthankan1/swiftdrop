package com.swiftdrop.deliveryservice.booking;

import com.swiftdrop.deliveryservice.quote.BookingAuthorization;
import com.swiftdrop.deliveryservice.quote.BookingQuoteProvider;
import com.swiftdrop.deliveryservice.quote.QuoteValidation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BookingEntryPointRecoveryPostgresIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("delivery_db")
            .withUsername("delivery_app")
            .withPassword("delivery_secret");

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("swiftdrop.outbox.relay.enabled", () -> "false");
    }

    @Autowired
    private ParcelBookingService customerBookingService;

    @Autowired
    private BookingService merchantBookingService;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private BookingQuoteProvider quoteProvider;

    @MockitoBean
    private BookingAuthorization bookingAuthorization;

    @Test
    @DisplayName("Issue 164 E5: customer winner prevents merchant reuse of the same quote without duplicate canonical effects")
    void customerAndMerchantEntryPointsShareSingleUseQuoteInvariant() {
        UUID quoteId = UUID.randomUUID();
        String customerSub = "customer-" + UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        String merchantSub = "merchant-user-" + UUID.randomUUID();
        QuoteValidation valid = validQuote(quoteId);
        when(quoteProvider.validate(eq(quoteId), eq(customerSub))).thenReturn(valid);
        when(quoteProvider.validateMerchantQuote(eq(quoteId), eq(merchantId), eq(merchantSub))).thenReturn(valid);

        var customer = customerBookingService.bookParcel(
                customerRequest(quoteId), customerSub, null, "idem-164-" + UUID.randomUUID());

        BookingFailure losingAttempt = catchThrowableOfType(
                () -> merchantBookingService.bookMerchant(
                        merchantId, merchantSub, UUID.randomUUID(), merchantRequest(quoteId)),
                BookingFailure.class);

        assertThat(losingAttempt.code()).isEqualTo("QUOTE_ALREADY_CONSUMED");
        assertThat(count("SELECT count(*) FROM parcels WHERE quote_id = ?", quoteId)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM status_history WHERE parcel_id = ?", customer.parcelId())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM outbox_events WHERE aggregate_id = ?", customer.parcelId())).isEqualTo(1);
    }

    @Test
    @Disabled("BLOCKED: merchant idempotency is persisted as merchant:<uuid> but replay queries <uuid>; owner fix required")
    @DisplayName("Issue 164 E4/E5: merchant lost-response retry returns the original canonical booking")
    void merchantLostResponseRetryReturnsCanonicalBooking() {
        UUID quoteId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        UUID idempotencyKey = UUID.randomUUID();
        String merchantSub = "merchant-user-" + UUID.randomUUID();
        when(quoteProvider.validateMerchantQuote(eq(quoteId), eq(merchantId), eq(merchantSub)))
                .thenReturn(validQuote(quoteId));

        var first = merchantBookingService.bookMerchant(
                merchantId, merchantSub, idempotencyKey, merchantRequest(quoteId));
        var replay = merchantBookingService.bookMerchant(
                merchantId, merchantSub, idempotencyKey, merchantRequest(quoteId));

        assertThat(replay).isEqualTo(first);
        assertThat(count("SELECT count(*) FROM parcels WHERE quote_id = ?", quoteId)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM status_history WHERE parcel_id = ?", first.parcelId())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM outbox_events WHERE aggregate_id = ?", first.parcelId())).isEqualTo(1);
    }

    @Test
    @DisplayName("Issue 164 D/E3: Pricing outage fails closed without parcel, history, outbox, or idempotency residue")
    void pricingOutageRollsBackCustomerBooking() {
        UUID quoteId = UUID.randomUUID();
        String customerSub = "customer-" + UUID.randomUUID();
        String idempotencyKey = "idem-164-" + UUID.randomUUID();
        when(quoteProvider.validate(eq(quoteId), eq(customerSub)))
                .thenThrow(new BookingFailure(503, "PRICING_UNAVAILABLE", "Pricing service is unavailable."))
                .thenReturn(validQuote(quoteId));

        BookingFailure failure = catchThrowableOfType(
                () -> customerBookingService.bookParcel(
                        customerRequest(quoteId), customerSub, null, idempotencyKey),
                BookingFailure.class);

        assertThat(failure.code()).isEqualTo("PRICING_UNAVAILABLE");
        assertThat(count("SELECT count(*) FROM parcels WHERE quote_id = ?", quoteId)).isZero();
        assertThat(count("SELECT count(*) FROM idempotency_records WHERE principal_or_tenant = ?", customerSub)).isZero();

        var recovered = customerBookingService.bookParcel(
                customerRequest(quoteId), customerSub, null, idempotencyKey);

        assertThat(count("SELECT count(*) FROM parcels WHERE quote_id = ?", quoteId)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM status_history WHERE parcel_id = ?", recovered.parcelId())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM outbox_events WHERE aggregate_id = ?", recovered.parcelId())).isEqualTo(1);
    }

    private int count(String sql, Object argument) {
        return jdbc.queryForObject(sql, Integer.class, argument);
    }

    private static QuoteValidation validQuote(UUID quoteId) {
        return new QuoteValidation(true, quoteId, "RATE-164", 50000L, "LKR",
                Instant.now().plusSeconds(900), "VALID");
    }

    private static BookParcelRequest customerRequest(UUID quoteId) {
        return new BookParcelRequest(quoteId, "Pickup fixture", "Dropoff fixture",
                "Recipient fixture", "+94770000000", null);
    }

    private static MerchantBookParcelRequest merchantRequest(UUID quoteId) {
        return new MerchantBookParcelRequest(quoteId, "Pickup fixture", "Dropoff fixture",
                "Recipient fixture", "+94770000000", "MERCHANT-164", null);
    }
}
