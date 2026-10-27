package com.swiftdrop.deliveryservice.booking;

import com.swiftdrop.deliveryservice.core.ParcelStatus;
import com.swiftdrop.deliveryservice.query.dto.ParcelDetailResponse;
import com.swiftdrop.deliveryservice.quote.BookingAuthorization;
import com.swiftdrop.deliveryservice.quote.BookingQuoteProvider;
import com.swiftdrop.deliveryservice.quote.QuoteValidation;
import jakarta.validation.Validator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Common booking intake domain service.
 * Enforces atomic parcel creation, status audit append, transactional outbox publication,
 * and tenant-scoped idempotency guarantees across both Customer and Merchant order channels.
 */
@Service
public class BookingService {

    private final BookingRepository repository;
    private final BookingQuoteProvider quotes;
    private final JsonMapper json;
    private final Clock clock;
    private final BookingAuthorization authorization;
    private final Validator validator;
    private final TransactionTemplate transaction;

    public BookingService(BookingRepository repository,
                          BookingQuoteProvider quotes,
                          BookingAuthorization authorization,
                          JsonMapper json,
                          Clock clock,
                          Validator validator,
                          PlatformTransactionManager manager) {
        this.repository = repository;
        this.quotes = quotes;
        this.authorization = authorization;
        this.json = json;
        this.clock = clock;
        this.validator = validator;
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /**
     * Customer parcel booking intake (Foundation Issue #20).
     */
    public ParcelBooking book(BookingActor actor, UUID key, BookParcelRequest request) {
        if (actor == null) throw failure(401, "UNAUTHENTICATED", "Authentication required.");
        if (key == null) throw failure(400, "INVALID_REQUEST", "Idempotency-Key is required for the foundation command.");
        if (request == null) throw failure(400, "INVALID_REQUEST", "Booking request is required.");

        var violations = validator.validate(request);
        if (!violations.isEmpty()) {
            throw BookingFailure.validation(violations.stream()
                    .map(v -> new BookingFailure.InvalidParameter(v.getPropertyPath().toString(), v.getMessage()))
                    .sorted(Comparator.comparing(BookingFailure.InvalidParameter::field)).toList());
        }

        return transaction.execute(status -> create(actor.subject(), key, request));
    }

    private ParcelBooking create(String subject, UUID key, BookParcelRequest request) {
        String digest = digest(json.writeValueAsString(request));
        repository.lock(subject, key);

        var replay = repository.replay(subject, key);
        if (replay.isPresent()) {
            if (!replay.get().digest().equals(digest)) {
                throw failure(409, "IDEMPOTENCY_CONFLICT", "Idempotency key was used for a different request.");
            }
            if (replay.get().response() != null) {
                return replay.get().response();
            }
            if (replay.get().storedResultJson() != null) {
                return json.readValue(replay.get().storedResultJson(), ParcelBooking.class);
            }
        }

        authorization.requireAllowed(subject, request);
        QuoteValidation quote = quotes.validate(request.quoteId(), subject);
        Instant now = clock.instant();
        validate(quote, request.quoteId(), now);

        if (repository.quoteUsed(request.quoteId())) {
            throw failure(409, "QUOTE_ALREADY_CONSUMED", "Quote already booked.");
        }

        for (int attempt = 0; attempt < 5; attempt++) {
            UUID id = UUID.randomUUID();
            String tracking = "SWD-" + DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC).format(now)
                    + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);

            var parcel = new ParcelBooking(id, tracking, subject, request.quoteId(), "CREATED", 1,
                    quote.verifiedAmountMinorUnits(), quote.currency(), quote.rateVersion(),
                    request.pickupAddress(), request.dropoffAddress(), now);

            if (repository.insert(parcel, request, json.writeValueAsString(quote))) {
                UUID eventId = UUID.randomUUID();
                var payload = new LinkedHashMap<String, Object>();
                payload.put("parcelId", id);
                payload.put("trackingNumber", tracking);
                payload.put("oldStatus", null);
                payload.put("newStatus", "CREATED");

                var event = Map.of(
                        "eventId", eventId,
                        "eventType", "parcel.status.changed.v1",
                        "schemaVersion", "1.0.0",
                        "aggregateId", id,
                        "aggregateType", "PARCEL",
                        "aggregateVersion", 1,
                        "occurredAt", now,
                        "correlationId", key.toString(),
                        "producer", "delivery-operations",
                        "payload", payload
                );

                repository.finish(parcel, key, digest, eventId, json.writeValueAsString(event));
                return parcel;
            }

            if (repository.quoteUsed(request.quoteId())) {
                throw failure(409, "QUOTE_ALREADY_CONSUMED", "Quote already booked.");
            }
        }

        throw failure(503, "BOOKING_UNAVAILABLE", "Booking could not be completed. Retry with the same key.");
    }

    /**
     * Merchant automated order booking runtime (Issue #129 / N10-B12).
     */
    public ParcelDetailResponse bookMerchant(UUID merchantId, String callerSub, UUID key, MerchantBookParcelRequest request) {
        if (merchantId == null) throw failure(401, "UNAUTHENTICATED", "Merchant identity is required.");
        if (key == null) throw failure(400, "INVALID_REQUEST", "Idempotency-Key is required.");
        if (request == null) throw failure(400, "INVALID_REQUEST", "Booking request is required.");

        var violations = validator.validate(request);
        if (!violations.isEmpty()) {
            throw BookingFailure.validation(violations.stream()
                    .map(v -> new BookingFailure.InvalidParameter(v.getPropertyPath().toString(), v.getMessage()))
                    .sorted(Comparator.comparing(BookingFailure.InvalidParameter::field)).toList());
        }

        return transaction.execute(status -> createMerchant(merchantId, callerSub, key, request));
    }

    private ParcelDetailResponse createMerchant(UUID merchantId, String callerSub, UUID key, MerchantBookParcelRequest request) {
        String digest = digest(json.writeValueAsString(request));
        String principal = "merchant:" + merchantId;
        repository.lock(principal, key);

        var replay = repository.replay(principal, key);
        if (replay.isPresent()) {
            if (!replay.get().digest().equals(digest)) {
                throw failure(409, "IDEMPOTENCY_CONFLICT", "Idempotency key was used for a different request.");
            }
            if (replay.get().storedResultJson() != null) {
                return json.readValue(replay.get().storedResultJson(), ParcelDetailResponse.class);
            }
        }

        // Validate quote via Pricing gRPC with verified merchant tenant delegation
        QuoteValidation quote = quotes.validateMerchantQuote(request.quoteId(), merchantId, callerSub);
        Instant now = clock.instant();
        validate(quote, request.quoteId(), now);

        if (repository.quoteUsed(request.quoteId())) {
            throw failure(409, "QUOTE_ALREADY_CONSUMED", "Quote already booked.");
        }

        String senderSub = (callerSub != null && !callerSub.isBlank()) ? callerSub : principal;

        for (int attempt = 0; attempt < 5; attempt++) {
            UUID parcelId = UUID.randomUUID();
            String trackingNumber = "SWD-" + DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC).format(now)
                    + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);

            ParcelDetailResponse response = new ParcelDetailResponse(
                    parcelId,
                    trackingNumber,
                    senderSub,
                    merchantId,
                    ParcelStatus.CREATED,
                    1L,
                    2.50, // default standard package weight
                    "STANDARD",
                    quote.verifiedAmountMinorUnits(),
                    quote.currency(),
                    quote.rateVersion(),
                    request.pickupAddress(),
                    request.dropoffAddress(),
                    null,
                    now
            );

            if (repository.insertMerchantParcel(
                    parcelId,
                    trackingNumber,
                    senderSub,
                    merchantId,
                    request.quoteId(),
                    quote.rateVersion(),
                    quote.verifiedAmountMinorUnits(),
                    quote.currency(),
                    request.merchantReference(),
                    request.pickupAddress(),
                    request.dropoffAddress(),
                    request.recipientName(),
                    request.recipientPhone(),
                    request.deliveryNotes(),
                    json.writeValueAsString(quote),
                    now
            )) {
                UUID eventId = UUID.randomUUID();
                var payload = new LinkedHashMap<String, Object>();
                payload.put("parcelId", parcelId.toString());
                payload.put("trackingNumber", trackingNumber);
                payload.put("oldStatus", null);
                payload.put("newStatus", "CREATED");
                payload.put("merchantId", merchantId.toString());
                payload.put("merchantReference", request.merchantReference());
                // Strictly zero customer street address, recipient phone, or PII in Kafka event

                var event = Map.of(
                        "eventId", eventId.toString(),
                        "eventType", "parcel.status.changed.v1",
                        "schemaVersion", "1.0.0",
                        "aggregateId", parcelId.toString(),
                        "aggregateType", "PARCEL",
                        "aggregateVersion", 1,
                        "occurredAt", now.toString(),
                        "correlationId", key.toString(),
                        "producer", "delivery-operations",
                        "payload", payload
                );

                repository.finishMerchant(
                        parcelId,
                        senderSub,
                        principal,
                        key,
                        digest,
                        eventId,
                        json.writeValueAsString(event),
                        json.writeValueAsString(response),
                        now
                );

                return response;
            }

            if (repository.quoteUsed(request.quoteId())) {
                throw failure(409, "QUOTE_ALREADY_CONSUMED", "Quote already booked.");
            }
        }

        throw failure(503, "BOOKING_UNAVAILABLE", "Booking could not be completed. Retry with the same key.");
    }

    private void validate(QuoteValidation q, UUID requestedQuoteId, Instant now) {
        if (q == null) throw failure(503, "QUOTE_PROVIDER_INVALID", "Quote validation is unavailable.");
        if (requestedQuoteId == null || !requestedQuoteId.equals(q.quoteId())) {
            throw failure(503, "QUOTE_PROVIDER_INVALID", "Quote validation is unavailable.");
        }
        if (!q.isValid()) {
            if ("PERMISSION_DENIED".equals(q.validationCode())) {
                throw failure(403, "QUOTE_FORBIDDEN", "Tenant is not authorized to use this quote.");
            }
            if ("QUOTE_NOT_FOUND".equals(q.validationCode()) || "NOT_FOUND".equals(q.validationCode())) {
                throw failure(404, "QUOTE_NOT_FOUND", "Quote was not found.");
            }
            if ("QUOTE_EXPIRED".equals(q.validationCode())) {
                throw failure(422, "QUOTE_EXPIRED", "Quote expired. Request a new quote.");
            }
            if ("RATE_SUPERSEDED".equals(q.validationCode())) {
                throw failure(422, "QUOTE_RATE_SUPERSEDED", "Quote rate is no longer eligible.");
            }
            if ("DEADLINE_EXCEEDED".equals(q.validationCode()) || "UNAVAILABLE".equals(q.validationCode())) {
                throw failure(503, "PRICING_UNAVAILABLE", "Pricing service is currently unavailable.");
            }
            throw failure(422, "QUOTE_INELIGIBLE", "Quote is not eligible for booking.");
        }
        if (!"VALID".equals(q.validationCode()) || q.expiresAt() == null || q.currency() == null
                || !q.currency().matches("[A-Z]{3}") || q.rateVersion() == null || q.rateVersion().isBlank()
                || q.verifiedAmountMinorUnits() < 0) {
            throw failure(503, "QUOTE_PROVIDER_INVALID", "Quote validation is unavailable.");
        }
        if (!q.expiresAt().isAfter(now)) {
            throw failure(422, "QUOTE_EXPIRED", "Quote expired. Request a new quote.");
        }
    }

    private static String digest(String input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static BookingFailure failure(int status, String code, String detail) {
        return new BookingFailure(status, code, detail);
    }
}
