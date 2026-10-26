package com.swiftdrop.pricing.grpc;

import com.google.protobuf.Timestamp;
import com.swiftdrop.pricing.domain.QuoteSnapshot;
import com.swiftdrop.pricing.domain.RateVersionStatus;
import com.swiftdrop.pricing.persistence.*;
import com.swiftdrop.pricing.v1.*;
import com.swiftdrop.security.grpc.DelegatedActorContext;
import com.swiftdrop.security.grpc.SwiftDropGrpcContext;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Authoritative gRPC provider implementation for PricingInternalService.
 *
 * Implements:
 * - IF-GRPC-04: ValidateQuote for Delivery Operations parcel booking authorization.
 *   Enforces machine authentication, delegated actor/tenant binding, immutable snapshot validation,
 *   strict non-recalculation, and fail-closed security.
 * - GetPricingAdminSummary: Bounded, read-only zone and rate-version summaries for authorized administrators.
 */
@Service
@Transactional(readOnly = true)
public class PricingInternalServiceImpl extends PricingInternalServiceGrpc.PricingInternalServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(PricingInternalServiceImpl.class);

    private static final Pattern ISO_CURRENCY_PATTERN = Pattern.compile("^[A-Za-z]{3}$");
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 50;

    private final QuoteRepository quoteRepository;
    private final ZoneRepository zoneRepository;
    private final RateVersionRepository rateVersionRepository;
    private final RateRuleRepository rateRuleRepository;
    private final Clock clock;

    public PricingInternalServiceImpl(
            QuoteRepository quoteRepository,
            ZoneRepository zoneRepository,
            RateVersionRepository rateVersionRepository,
            RateRuleRepository rateRuleRepository,
            Clock clock) {
        this.quoteRepository = Objects.requireNonNull(quoteRepository, "quoteRepository must not be null");
        this.zoneRepository = Objects.requireNonNull(zoneRepository, "zoneRepository must not be null");
        this.rateVersionRepository = Objects.requireNonNull(rateVersionRepository, "rateVersionRepository must not be null");
        this.rateRuleRepository = Objects.requireNonNull(rateRuleRepository, "rateRuleRepository must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public void validateQuote(ValidateQuoteRequest request, StreamObserver<ValidateQuoteResponse> responseObserver) {
        try {
            // 1. Deadline / cancellation check
            if (Context.current().isCancelled()) {
                responseObserver.onError(Status.DEADLINE_EXCEEDED
                        .withDescription("Request deadline exceeded or cancelled")
                        .asRuntimeException());
                return;
            }

            // 2. Validate quote_id format
            String quoteIdStr = request.getQuoteId();
            if (quoteIdStr == null || quoteIdStr.isBlank()) {
                responseObserver.onError(Status.INVALID_ARGUMENT
                        .withDescription("quote_id must not be blank")
                        .asRuntimeException());
                return;
            }

            UUID quoteId;
            try {
                quoteId = UUID.fromString(quoteIdStr.trim());
            } catch (IllegalArgumentException ex) {
                responseObserver.onError(Status.INVALID_ARGUMENT
                        .withDescription("quote_id is not a valid UUID format: " + quoteIdStr)
                        .asRuntimeException());
                return;
            }

            // 3. Validate monetary arguments
            if (request.getExpectedAmountMinorUnits() < 0) {
                responseObserver.onError(Status.INVALID_ARGUMENT
                        .withDescription("expected_amount_minor_units cannot be negative")
                        .asRuntimeException());
                return;
            }

            String expectedCurrency = request.getExpectedCurrency();
            if (expectedCurrency != null && !expectedCurrency.isBlank()) {
                if (!ISO_CURRENCY_PATTERN.matcher(expectedCurrency.trim()).matches()) {
                    responseObserver.onError(Status.INVALID_ARGUMENT
                        .withDescription("expected_currency must be a valid 3-letter ISO-4217 currency code")
                        .asRuntimeException());
                    return;
                }
            }

            // 4. Verify delegated actor identity
            DelegatedActorContext actor = SwiftDropGrpcContext.currentDelegatedActor().orElse(null);
            if (actor == null) {
                responseObserver.onError(Status.UNAUTHENTICATED
                        .withDescription("Delegated actor context is required for quote verification")
                        .asRuntimeException());
                return;
            }

            // 5. Authoritative snapshot lookup from pricing_db
            Optional<QuoteEntity> quoteOpt = quoteRepository.findById(quoteId);
            if (quoteOpt.isEmpty()) {
                // Unknown quote: deterministic business response
                ValidateQuoteResponse notFoundResponse = ValidateQuoteResponse.newBuilder()
                        .setIsValid(false)
                        .setQuoteId(quoteIdStr)
                        .setValidationCode("QUOTE_NOT_FOUND")
                        .build();
                responseObserver.onNext(notFoundResponse);
                responseObserver.onCompleted();
                return;
            }

            QuoteSnapshot quote = quoteOpt.get().toDomain();

            // 6. Enforce resource ownership and tenant isolation
            // Rule A: Anonymous quotes cannot be used for authenticated booking without approved binding
            if (quote.isAnonymous()) {
                responseObserver.onError(Status.PERMISSION_DENIED
                        .withDescription("Anonymous quote cannot be used for authenticated parcel booking without approved binding")
                        .asRuntimeException());
                return;
            }

            // Rule B: Customer actor can only use their own quote
            if (actor.isCustomer() && !actor.isAdmin()) {
                if (quote.getSubjectSub() == null || !quote.getSubjectSub().equals(actor.actorSub())) {
                    responseObserver.onError(Status.PERMISSION_DENIED
                            .withDescription("Actor is not authorized to use this quote")
                            .asRuntimeException());
                    return;
                }
            }

            // Rule C: Merchant actor can only use quotes bound to their merchant tenant
            if (actor.isMerchant() && !actor.isAdmin()) {
                if (quote.getTenantId() == null || !quote.getTenantId().equals(actor.tenantId())) {
                    responseObserver.onError(Status.PERMISSION_DENIED
                            .withDescription("Merchant tenant is not authorized to use this quote")
                            .asRuntimeException());
                    return;
                }
            }

            // Rule D: Admin actor does not automatically authorize a customer booking with an unrelated quote
            if (actor.isAdmin()) {
                if (quote.getSubjectSub() != null && !quote.getSubjectSub().equals(actor.actorSub())) {
                    responseObserver.onError(Status.PERMISSION_DENIED
                            .withDescription("Admin actor is not authorized to use unrelated customer quote")
                            .asRuntimeException());
                    return;
                }
                if (quote.getTenantId() != null && (actor.tenantId() == null || !quote.getTenantId().equals(actor.tenantId()))) {
                    responseObserver.onError(Status.PERMISSION_DENIED
                            .withDescription("Admin actor is not authorized to use unrelated merchant tenant quote")
                            .asRuntimeException());
                    return;
                }
            }

            // Rule E: Disallow callers without recognized role
            if (!actor.isCustomer() && !actor.isMerchant() && !actor.isAdmin()) {
                responseObserver.onError(Status.PERMISSION_DENIED
                        .withDescription("Delegated actor role does not possess quote verification permissions")
                        .asRuntimeException());
                return;
            }

            // 7. Verify quote expiry using injected Clock
            if (quote.isExpired(clock)) {
                ValidateQuoteResponse expiredResponse = buildResponse(false, "QUOTE_EXPIRED", quote);
                responseObserver.onNext(expiredResponse);
                responseObserver.onCompleted();
                return;
            }

            // 8. Rate version assertion check (if supplied by caller)
            String expectedRateVersion = request.getExpectedRateVersion();
            if (expectedRateVersion != null && !expectedRateVersion.isBlank()) {
                if (!quote.getRateVersion().equals(expectedRateVersion.trim())) {
                    ValidateQuoteResponse supersededResponse = buildResponse(false, "RATE_SUPERSEDED", quote);
                    responseObserver.onNext(supersededResponse);
                    responseObserver.onCompleted();
                    return;
                }
            }

            // 9. Currency assertion check (if supplied by caller)
            if (expectedCurrency != null && !expectedCurrency.isBlank()) {
                if (!quote.getCurrency().equalsIgnoreCase(expectedCurrency.trim())) {
                    ValidateQuoteResponse mismatchResponse = buildResponse(false, "AMOUNT_MISMATCH", quote);
                    responseObserver.onNext(mismatchResponse);
                    responseObserver.onCompleted();
                    return;
                }
            }

            // 10. Amount assertion check (if supplied by caller)
            long expectedAmount = request.getExpectedAmountMinorUnits();
            if (expectedAmount > 0) {
                if (quote.getTotalAmountMinorUnits() != expectedAmount) {
                    ValidateQuoteResponse mismatchResponse = buildResponse(false, "AMOUNT_MISMATCH", quote);
                    responseObserver.onNext(mismatchResponse);
                    responseObserver.onCompleted();
                    return;
                }
            }

            // 11. Authoritative validation success: return original snapshot without recalculation
            ValidateQuoteResponse validResponse = buildResponse(true, "VALID", quote);
            responseObserver.onNext(validResponse);
            responseObserver.onCompleted();

        } catch (StatusRuntimeException ex) {
            responseObserver.onError(ex);
        } catch (DataAccessException ex) {
            log.error("Database access error during quote validation", ex);
            responseObserver.onError(Status.UNAVAILABLE
                    .withDescription("Pricing database is currently unavailable")
                    .asRuntimeException());
        } catch (Exception ex) {
            log.error("Unexpected error in validateQuote", ex);
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Internal pricing service error")
                    .asRuntimeException());
        }
    }

    @Override
    public void getPricingAdminSummary(PricingAdminSummaryRequest request, StreamObserver<PricingAdminSummaryResponse> responseObserver) {
        try {
            // 1. Deadline / cancellation check
            if (Context.current().isCancelled()) {
                responseObserver.onError(Status.DEADLINE_EXCEEDED
                        .withDescription("Request deadline exceeded or cancelled")
                        .asRuntimeException());
                return;
            }

            // 2. Security: Verify delegated actor possesses ROLE_ADMIN
            DelegatedActorContext actor = SwiftDropGrpcContext.currentDelegatedActor().orElse(null);
            if (actor == null || !actor.isAdmin()) {
                responseObserver.onError(Status.PERMISSION_DENIED
                        .withDescription("Admin summary requires an authenticated delegated actor with ROLE_ADMIN")
                        .asRuntimeException());
                return;
            }

            // 3. Strict bounding and pagination (1 to 50, default 20)
            int page = Math.max(0, request.getPage());
            int pageSize = request.getPageSize() > 0 ? Math.min(request.getPageSize(), MAX_PAGE_SIZE) : DEFAULT_PAGE_SIZE;

            // 4. Retrieve zones
            List<ZoneEntity> allZones;
            if (request.getActiveOnly()) {
                allZones = zoneRepository.findByActiveTrueOrderByZoneCodeAsc();
            } else {
                allZones = zoneRepository.findAll();
            }

            int totalZones = allZones.size();
            int fromZone = Math.min(page * pageSize, totalZones);
            int toZone = Math.min(fromZone + pageSize, totalZones);
            List<ZoneEntity> pagedZones = allZones.subList(fromZone, toZone);

            List<PricingZoneSummaryItem> zoneItems = pagedZones.stream()
                    .map(z -> PricingZoneSummaryItem.newBuilder()
                            .setZoneId(z.getId() != null ? z.getId().toString() : "")
                            .setZoneCode(z.getZoneCode())
                            .setName(z.getName())
                            .setDescription(z.getDescription() != null ? z.getDescription() : "")
                            .setIsActive(z.isActive())
                            .build())
                    .toList();

            // 5. Retrieve rate versions
            List<RateVersionEntity> allVersions = rateVersionRepository.findAllByOrderByCreatedAtDesc();
            if (request.getActiveOnly()) {
                allVersions = allVersions.stream()
                        .filter(v -> v.getStatus() == RateVersionStatus.ACTIVE)
                        .toList();
            }

            int totalVersions = allVersions.size();
            int fromVersion = Math.min(page * pageSize, totalVersions);
            int toVersion = Math.min(fromVersion + pageSize, totalVersions);
            List<RateVersionEntity> pagedVersions = allVersions.subList(fromVersion, toVersion);

            List<RateVersionSummaryItem> versionItems = pagedVersions.stream()
                    .map(v -> {
                        int ruleCount = rateRuleRepository.findByRateVersionId(v.getId()).size();
                        RateVersionSummaryItem.Builder builder = RateVersionSummaryItem.newBuilder()
                                .setRateVersionId(v.getId() != null ? v.getId().toString() : "")
                                .setVersionCode(v.getVersionCode())
                                .setCurrency(v.getCurrency())
                                .setStatus(v.getStatus().name())
                                .setRuleCount(ruleCount);
                        if (v.getCreatedAt() != null) {
                            builder.setCreatedAt(toTimestamp(v.getCreatedAt()));
                        }
                        return builder.build();
                    })
                    .toList();

            // 6. Build bounded response
            PricingAdminSummaryResponse response = PricingAdminSummaryResponse.newBuilder()
                    .addAllZones(zoneItems)
                    .addAllRateVersions(versionItems)
                    .setTotalZones(totalZones)
                    .setTotalRateVersions(totalVersions)
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();

        } catch (StatusRuntimeException ex) {
            responseObserver.onError(ex);
        } catch (DataAccessException ex) {
            log.error("Database access error during getPricingAdminSummary", ex);
            responseObserver.onError(Status.UNAVAILABLE
                    .withDescription("Pricing database is currently unavailable")
                    .asRuntimeException());
        } catch (Exception ex) {
            log.error("Unexpected error in getPricingAdminSummary", ex);
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Internal pricing service error")
                    .asRuntimeException());
        }
    }

    private static ValidateQuoteResponse buildResponse(boolean isValid, String validationCode, QuoteSnapshot quote) {
        ValidateQuoteResponse.Builder builder = ValidateQuoteResponse.newBuilder()
                .setIsValid(isValid)
                .setQuoteId(quote.getQuoteId().toString())
                .setRateVersion(quote.getRateVersion())
                .setVerifiedAmountMinorUnits(quote.getTotalAmountMinorUnits())
                .setCurrency(quote.getCurrency())
                .setExpiresAt(toTimestamp(quote.getExpiresAt()))
                .setValidationCode(validationCode)
                // Additive backward-compatible snapshot fields
                .setPickupZone(quote.getPickupZone())
                .setDropoffZone(quote.getDropoffZone())
                .setSubjectSub(quote.getSubjectSub() != null ? quote.getSubjectSub() : "")
                .setTenantId(quote.getTenantId() != null ? quote.getTenantId() : "")
                .setIsAnonymous(quote.isAnonymous())
                .setBaseFeeMinorUnits(quote.getBaseFeeMinorUnits())
                .setWeightSurchargeMinorUnits(quote.getWeightSurchargeMinorUnits());

        return builder.build();
    }

    private static Timestamp toTimestamp(Instant instant) {
        if (instant == null) {
            return Timestamp.getDefaultInstance();
        }
        return Timestamp.newBuilder()
                .setSeconds(instant.getEpochSecond())
                .setNanos(instant.getNano())
                .build();
    }
}
