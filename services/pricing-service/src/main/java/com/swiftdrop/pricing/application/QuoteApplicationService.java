package com.swiftdrop.pricing.application;

import com.swiftdrop.pricing.api.dto.QuoteRequest;
import com.swiftdrop.pricing.api.dto.QuoteResponse;
import com.swiftdrop.pricing.domain.PricingCalculation;
import com.swiftdrop.pricing.domain.QuoteSnapshot;
import com.swiftdrop.pricing.error.QuoteNotFoundException;
import com.swiftdrop.pricing.persistence.QuoteEntity;
import com.swiftdrop.pricing.persistence.QuoteRepository;
import com.swiftdrop.pricing.persistence.RateVersionEntity;
import com.swiftdrop.pricing.persistence.RateVersionRepository;
import com.swiftdrop.pricing.security.CallerContext;
import com.swiftdrop.pricing.security.QuoteOwnershipPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Application service orchestrating quote calculation, snapshot persistence, and secure retrieval.
 */
@Service
public class QuoteApplicationService {

    private final PricingCalculationService calculationService;
    private final RateVersionRepository rateVersionRepository;
    private final QuoteRepository quoteRepository;
    private final QuoteOwnershipPolicy ownershipPolicy;
    private final Clock clock;
    private final Duration quoteValidityDuration;

    public QuoteApplicationService(
            PricingCalculationService calculationService,
            RateVersionRepository rateVersionRepository,
            QuoteRepository quoteRepository,
            QuoteOwnershipPolicy ownershipPolicy,
            Clock clock,
            @Value("${swiftdrop.pricing.quote-validity-seconds:900}") long validitySeconds) {
        this.calculationService = calculationService;
        this.rateVersionRepository = rateVersionRepository;
        this.quoteRepository = quoteRepository;
        this.ownershipPolicy = ownershipPolicy;
        this.clock = clock;
        this.quoteValidityDuration = Duration.ofSeconds(validitySeconds);
    }

    /**
     * Calculates price using PricingCalculationService and persists an immutable QuoteSnapshot.
     */
    @Transactional
    public QuoteResponse createQuote(QuoteRequest request, CallerContext caller) {
        Objects.requireNonNull(request, "QuoteRequest cannot be null");
        Objects.requireNonNull(caller, "CallerContext cannot be null");

        // 1. Invoke deterministic calculation service
        CalculationCommand command = CalculationCommand.of(
                request.pickupZone(),
                request.dropoffZone(),
                request.lengthCm(),
                request.widthCm(),
                request.heightCm(),
                request.weightKg()
        );
        PricingCalculation calculation = calculationService.calculatePrice(command);

        // 2. Resolve RateVersionEntity ID for foreign key relationship
        RateVersionEntity rateVersionEntity = rateVersionRepository.findByVersionCode(calculation.getRateVersion())
                .orElseThrow(() -> new IllegalStateException("Active rate version not found in database: " + calculation.getRateVersion()));

        // 3. Compute creation and UTC expiry timestamps
        Instant createdAt = Instant.now(clock);
        Instant expiresAt = createdAt.plus(quoteValidityDuration);

        // 4. Generate opaque quote UUID
        UUID quoteId = UUID.randomUUID();

        // 5. Construct immutable QuoteSnapshot
        QuoteSnapshot snapshot = new QuoteSnapshot(
                quoteId,
                calculation.getRateVersion(),
                rateVersionEntity.getId(),
                calculation.getEvaluatedRuleId(),
                calculation.getPickupZone(),
                calculation.getDropoffZone(),
                request.lengthCm(),
                request.widthCm(),
                request.heightCm(),
                calculation.getPhysicalWeightKg(),
                calculation.getVolumetricWeightKg(),
                calculation.getChargeableWeightKg(),
                calculation.getSizeCategory(),
                calculation.getBaseFeeMinorUnits(),
                calculation.getWeightSurchargeMinorUnits(),
                calculation.getTotalAmountMinorUnits(),
                calculation.getCurrency(),
                caller.isAnonymous() ? null : caller.subject(),
                caller.isAnonymous() ? null : caller.tenantId(),
                caller.isAnonymous(),
                createdAt,
                expiresAt
        );

        // 6. Persist transactionally
        QuoteEntity entity = QuoteEntity.fromDomain(snapshot);
        quoteRepository.saveAndFlush(entity);

        // 7. Map to QuoteResponse
        return QuoteResponse.fromDomain(snapshot, clock);
    }

    /**
     * Safely retrieves an existing quote snapshot, enforcing ownership policy.
     */
    @Transactional(readOnly = true)
    public QuoteResponse getQuote(UUID quoteId, CallerContext caller) {
        Objects.requireNonNull(quoteId, "quoteId cannot be null");
        Objects.requireNonNull(caller, "CallerContext cannot be null");

        QuoteEntity entity = quoteRepository.findById(quoteId)
                .orElseThrow(() -> new QuoteNotFoundException("Quote not found with id: " + quoteId));

        QuoteSnapshot snapshot = entity.toDomain();

        if (!ownershipPolicy.canAccessQuote(snapshot, caller)) {
            // Fail closed: treat unauthorized access as 404 to avoid leaking quote existence
            throw new QuoteNotFoundException("Quote not found with id: " + quoteId);
        }

        return QuoteResponse.fromDomain(snapshot, clock);
    }

    /**
     * Domain lookup method for internal quote verification (e.g., later gRPC provider in Issue #114).
     */
    @Transactional(readOnly = true)
    public Optional<QuoteSnapshot> findQuoteById(UUID quoteId) {
        return quoteRepository.findById(quoteId).map(QuoteEntity::toDomain);
    }
}
