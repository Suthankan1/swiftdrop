package com.swiftdrop.deliveryservice.booking;

import com.swiftdrop.deliveryservice.core.Parcel;
import com.swiftdrop.deliveryservice.core.ParcelRepository;
import com.swiftdrop.deliveryservice.core.ParcelStatus;
import com.swiftdrop.deliveryservice.idempotency.IdempotencyRecord;
import com.swiftdrop.deliveryservice.idempotency.IdempotencyRecordRepository;
import com.swiftdrop.deliveryservice.idempotency.IdempotencyStatus;
import com.swiftdrop.deliveryservice.outbox.OutboxEvent;
import com.swiftdrop.deliveryservice.outbox.OutboxEventFactory;
import com.swiftdrop.deliveryservice.outbox.OutboxEventRepository;
import com.swiftdrop.deliveryservice.query.dto.ParcelDetailResponse;
import com.swiftdrop.deliveryservice.quote.BookingQuoteProvider;
import com.swiftdrop.deliveryservice.quote.QuoteValidation;
import com.swiftdrop.deliveryservice.status.StatusHistory;
import com.swiftdrop.deliveryservice.status.StatusHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Domain Service responsible for transactional parcel booking, quote verification,
 * and de-duplicated idempotent booking execution.
 */
@Service
public class ParcelBookingService {

    private static final Logger log = LoggerFactory.getLogger(ParcelBookingService.class);

    private final ParcelRepository parcelRepository;
    private final StatusHistoryRepository statusHistoryRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final OutboxEventFactory outboxEventFactory;
    private final IdempotencyRecordRepository idempotencyRecordRepository;
    private final BookingQuoteProvider bookingQuoteProvider;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public ParcelBookingService(
            ParcelRepository parcelRepository,
            StatusHistoryRepository statusHistoryRepository,
            OutboxEventRepository outboxEventRepository,
            OutboxEventFactory outboxEventFactory,
            IdempotencyRecordRepository idempotencyRecordRepository,
            BookingQuoteProvider bookingQuoteProvider) {
        this(parcelRepository, statusHistoryRepository, outboxEventRepository, outboxEventFactory,
                idempotencyRecordRepository, bookingQuoteProvider, JsonMapper.builder().build(), Clock.systemUTC());
    }

    public ParcelBookingService(
            ParcelRepository parcelRepository,
            StatusHistoryRepository statusHistoryRepository,
            OutboxEventRepository outboxEventRepository,
            OutboxEventFactory outboxEventFactory,
            IdempotencyRecordRepository idempotencyRecordRepository,
            BookingQuoteProvider bookingQuoteProvider,
            JsonMapper jsonMapper,
            Clock clock) {
        this.parcelRepository = parcelRepository;
        this.statusHistoryRepository = statusHistoryRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.outboxEventFactory = outboxEventFactory;
        this.idempotencyRecordRepository = idempotencyRecordRepository;
        this.bookingQuoteProvider = bookingQuoteProvider;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    @Transactional
    public ParcelDetailResponse bookParcel(BookParcelRequest request, String callerSub, UUID merchantId, String idempotencyKey) {
        Objects.requireNonNull(request, "request cannot be null");
        Objects.requireNonNull(callerSub, "callerSub cannot be null");

        // 1. Idempotency Check
        String requestDigest = null;
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            requestDigest = computeDigest(request);
            Optional<IdempotencyRecord> existingRecord = idempotencyRecordRepository
                    .findByPrincipalOrTenantAndIdempotencyKey(callerSub, idempotencyKey.trim());

            if (existingRecord.isPresent()) {
                IdempotencyRecord record = existingRecord.get();
                if (!record.getRequestDigest().equals(requestDigest)) {
                    throw new BookingFailure(409, "IDEMPOTENCY_CONFLICT",
                            "Idempotency key was used with different parameters.");
                }
                if (record.getStatus() == IdempotencyStatus.COMPLETED && record.getStoredResult() != null) {
                    try {
                        return jsonMapper.readValue(record.getStoredResult(), ParcelDetailResponse.class);
                    } catch (Exception ex) {
                        log.warn("Failed to deserialize cached idempotency result: {}", ex.getMessage());
                    }
                }
                if (record.getStatus() == IdempotencyStatus.IN_PROGRESS) {
                    throw new BookingFailure(409, "IDEMPOTENCY_IN_PROGRESS",
                            "Booking is currently in progress for this idempotency key. Please retry shortly.");
                }
            }
        }

        // 2. Prevent Double Consumption of Quote in Delivery Operations
        if (parcelRepository.existsByQuoteId(request.quoteId())) {
            throw new BookingFailure(409, "QUOTE_ALREADY_CONSUMED",
                    "This quote has already been consumed for a previous booking.");
        }

        // 3. Authoritative Quote Validation via Pricing gRPC (IF-GRPC-04)
        QuoteValidation quote = bookingQuoteProvider.validate(request.quoteId(), callerSub);
        validateQuote(quote, request.quoteId());

        Instant now = clock.instant();

        // 4. Generate Unique Tracking Number (SWD-YYYYMMDD-XXXXXXXX)
        String datePrefix = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC).format(now);
        String trackingNumber = generateUniqueTrackingNumber(datePrefix);

        // 5. Persist Parcel Aggregate Root
        UUID parcelId = UUID.randomUUID();
        Parcel parcel = new Parcel(
                parcelId,
                trackingNumber,
                callerSub,
                merchantId,
                request.quoteId(),
                quote.rateVersion(),
                quote.verifiedAmountMinorUnits(),
                quote.currency() != null ? quote.currency() : "LKR",
                null
        );
        parcel = parcelRepository.saveAndFlush(parcel);

        // 6. Record Initial Status History (CREATED)
        StatusHistory history = new StatusHistory(
                UUID.randomUUID(),
                parcelId,
                null,
                ParcelStatus.CREATED,
                "INITIAL_CREATION",
                request.deliveryNotes(),
                null,
                callerSub,
                1L,
                now
        );
        statusHistoryRepository.save(history);

        // 7. Record Outbox Event Atomically
        OutboxEvent outboxEvent = outboxEventFactory.createParcelStatusChangedEvent(
                parcel,
                null,
                ParcelStatus.CREATED,
                "INITIAL_CREATION",
                null,
                idempotencyKey,
                now,
                1L
        );
        outboxEventRepository.save(outboxEvent);

        // 8. Construct Detail Response
        ParcelDetailResponse response = new ParcelDetailResponse(
                parcel.getParcelId(),
                parcel.getTrackingNumber(),
                parcel.getSenderSub(),
                parcel.getMerchantId(),
                parcel.getStatus(),
                parcel.getVersion(),
                parcel.getWeightKg() != null ? parcel.getWeightKg().doubleValue() : 0.0,
                "STANDARD",
                parcel.getAmountMinorUnits(),
                parcel.getCurrency(),
                parcel.getRateVersion(),
                request.pickupAddress(),
                request.dropoffAddress(),
                null,
                parcel.getCreatedAt()
        );

        // 9. Store Idempotency Result
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            try {
                String storedJson = jsonMapper.writeValueAsString(response);
                IdempotencyRecord record = new IdempotencyRecord(
                        UUID.randomUUID(),
                        callerSub,
                        idempotencyKey.trim(),
                        requestDigest,
                        IdempotencyStatus.COMPLETED,
                        storedJson,
                        now.plus(24, ChronoUnit.HOURS)
                );
                idempotencyRecordRepository.save(record);
            } catch (Exception ex) {
                log.warn("Failed to store idempotency record for key {}: {}", idempotencyKey, ex.getMessage());
            }
        }

        return response;
    }

    private void validateQuote(QuoteValidation quote, UUID requestedQuoteId) {
        if (quote == null) {
            throw new BookingFailure(503, "QUOTE_PROVIDER_INVALID", "Pricing service is currently unavailable.");
        }
        if (!requestedQuoteId.equals(quote.quoteId())) {
            throw new BookingFailure(503, "QUOTE_PROVIDER_INVALID", "Pricing returned mismatched quote identification.");
        }
        if (!quote.isValid()) {
            String code = quote.validationCode();
            if ("QUOTE_EXPIRED".equals(code)) {
                throw new BookingFailure(422, "QUOTE_EXPIRED", "Quote has expired. Please recalculate your quote.");
            }
            if ("RATE_SUPERSEDED".equals(code)) {
                throw new BookingFailure(422, "QUOTE_RATE_SUPERSEDED", "Quote rate version is no longer eligible.");
            }
            if ("QUOTE_NOT_FOUND".equals(code)) {
                throw new BookingFailure(404, "QUOTE_NOT_FOUND", "The requested quote was not found.");
            }
            if ("DEADLINE_EXCEEDED".equals(code) || "UNAVAILABLE".equals(code)) {
                throw new BookingFailure(503, "QUOTE_PROVIDER_UNAVAILABLE", "Pricing service is currently unavailable or timed out: " + code);
            }
            throw new BookingFailure(422, "QUOTE_INELIGIBLE", "Quote is not eligible for booking: " + code);
        }
        if (quote.expiresAt() != null && !quote.expiresAt().isAfter(clock.instant())) {
            throw new BookingFailure(422, "QUOTE_EXPIRED", "Quote has expired. Please recalculate your quote.");
        }
        if (quote.verifiedAmountMinorUnits() < 0) {
            throw new BookingFailure(503, "QUOTE_PROVIDER_INVALID", "Pricing verification returned negative amount.");
        }
    }

    private String generateUniqueTrackingNumber(String datePrefix) {
        for (int i = 0; i < 5; i++) {
            String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
            String trackingNumber = "SWD-" + datePrefix + "-" + suffix;
            if (!parcelRepository.existsByTrackingNumber(trackingNumber)) {
                return trackingNumber;
            }
        }
        throw new BookingFailure(503, "TRACKING_NUMBER_EXHAUSTED", "Unable to allocate unique tracking number. Please retry.");
    }

    private String computeDigest(BookParcelRequest request) {
        try {
            String canonical = jsonMapper.writeValueAsString(request);
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}

