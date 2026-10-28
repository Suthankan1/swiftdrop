package com.swiftdrop.pricing.application;

import com.swiftdrop.pricing.api.dto.QuoteRequest;
import com.swiftdrop.pricing.api.dto.QuoteResponse;
import com.swiftdrop.pricing.domain.Money;
import com.swiftdrop.pricing.domain.PricingCalculation;
import com.swiftdrop.pricing.domain.QuoteSnapshot;
import com.swiftdrop.pricing.domain.RateVersionStatus;
import com.swiftdrop.pricing.domain.SizeCategory;
import com.swiftdrop.pricing.error.QuoteNotFoundException;
import com.swiftdrop.pricing.persistence.QuoteEntity;
import com.swiftdrop.pricing.persistence.QuoteRepository;
import com.swiftdrop.pricing.persistence.RateVersionEntity;
import com.swiftdrop.pricing.persistence.RateVersionRepository;
import com.swiftdrop.pricing.security.CallerContext;
import com.swiftdrop.pricing.security.QuoteOwnershipPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QuoteApplicationServiceTest {

    @Mock
    private PricingCalculationService calculationService;

    @Mock
    private RateVersionRepository rateVersionRepository;

    @Mock
    private QuoteRepository quoteRepository;

    private final QuoteOwnershipPolicy ownershipPolicy = new QuoteOwnershipPolicy();
    private static final Instant FIXED_NOW = Instant.parse("2026-10-26T10:00:00Z");
    private final Clock clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);

    private QuoteApplicationService quoteService;

    @BeforeEach
    void setUp() {
        quoteService = new QuoteApplicationService(
                calculationService,
                rateVersionRepository,
                quoteRepository,
                ownershipPolicy,
                clock,
                900L,
                org.mockito.Mockito.mock(com.swiftdrop.pricing.persistence.PricingConfigurationLock.class)
        );
    }

    @Test
    @DisplayName("Should create and persist quote snapshot correctly")
    void shouldCreateAndPersistQuoteSnapshot() {
        QuoteRequest request = new QuoteRequest(
                "ZONE_1", "ZONE_2", new BigDecimal("2.50"), 30, 20, 15
        );
        CallerContext caller = CallerContext.authenticated("user-100", "merchant-200", Set.of("CUSTOMER"));

        UUID ruleId = UUID.randomUUID();
        PricingCalculation calculation = new PricingCalculation(
                "RATE-2026-V1", "LKR", "ZONE_1", "ZONE_2",
                new BigDecimal("2.50"), new BigDecimal("1.80"), new BigDecimal("2.50"),
                SizeCategory.MEDIUM,
                Money.ofMinorUnits(35000L, "LKR"),
                Money.ofMinorUnits(15000L, "LKR"),
                Money.ofMinorUnits(50000L, "LKR"),
                ruleId
        );
        when(calculationService.calculatePrice(any())).thenReturn(calculation);

        UUID rateVersionId = UUID.randomUUID();
        RateVersionEntity rateVersionEntity = new RateVersionEntity(
                rateVersionId, "RATE-2026-V1", "LKR", RateVersionStatus.ACTIVE, FIXED_NOW, "Active version");
        when(rateVersionRepository.findByVersionCode("RATE-2026-V1")).thenReturn(Optional.of(rateVersionEntity));

        QuoteResponse response = quoteService.createQuote(request, caller);

        assertThat(response).isNotNull();
        assertThat(response.quoteId()).isNotNull();
        assertThat(response.rateVersion()).isEqualTo("RATE-2026-V1");
        assertThat(response.pickupZone()).isEqualTo("ZONE_1");
        assertThat(response.dropoffZone()).isEqualTo("ZONE_2");
        assertThat(response.weightKg()).isEqualByComparingTo("2.50");
        assertThat(response.volumetricWeightKg()).isEqualByComparingTo("1.80");
        assertThat(response.chargeableWeightKg()).isEqualByComparingTo("2.50");
        assertThat(response.sizeCategory()).isEqualTo(SizeCategory.MEDIUM);
        assertThat(response.baseFeeMinorUnits()).isEqualTo(35000L);
        assertThat(response.weightSurchargeMinorUnits()).isEqualTo(15000L);
        assertThat(response.totalAmountMinorUnits()).isEqualTo(50000L);
        assertThat(response.currency()).isEqualTo("LKR");
        assertThat(response.createdAt()).isEqualTo(FIXED_NOW);
        assertThat(response.expiresAt()).isEqualTo(FIXED_NOW.plusSeconds(900));
        assertThat(response.isExpired()).isFalse();

        ArgumentCaptor<QuoteEntity> captor = ArgumentCaptor.forClass(QuoteEntity.class);
        verify(quoteRepository).saveAndFlush(captor.capture());
        QuoteEntity saved = captor.getValue();
        assertThat(saved.getSubjectSub()).isEqualTo("user-100");
        assertThat(saved.getTenantId()).isEqualTo("merchant-200");
        assertThat(saved.isAnonymous()).isFalse();
        assertThat(saved.getTotalAmountMinorUnits()).isEqualTo(50000L);
    }

    @Test
    @DisplayName("Should retrieve permitted quote successfully")
    void shouldRetrievePermittedQuote() {
        UUID quoteId = UUID.randomUUID();
        QuoteSnapshot snapshot = new QuoteSnapshot(
                quoteId, "RATE-2026-V1", UUID.randomUUID(), null,
                "ZONE_1", "ZONE_2", 30, 20, 15,
                new BigDecimal("2.50"), new BigDecimal("1.80"), new BigDecimal("2.50"),
                SizeCategory.MEDIUM, 35000L, 15000L, 50000L, "LKR",
                "customer-sub", null, false,
                FIXED_NOW, FIXED_NOW.plusSeconds(900)
        );
        QuoteEntity entity = QuoteEntity.fromDomain(snapshot);
        when(quoteRepository.findById(quoteId)).thenReturn(Optional.of(entity));

        CallerContext caller = CallerContext.authenticated("customer-sub", null, Set.of("CUSTOMER"));
        QuoteResponse response = quoteService.getQuote(quoteId, caller);

        assertThat(response.quoteId()).isEqualTo(quoteId);
        assertThat(response.totalAmountMinorUnits()).isEqualTo(50000L);
    }

    @Test
    @DisplayName("Should throw QuoteNotFoundException on unauthorized retrieval attempt (fail closed)")
    void shouldThrowNotFoundOnUnauthorizedAccess() {
        UUID quoteId = UUID.randomUUID();
        QuoteSnapshot snapshot = new QuoteSnapshot(
                quoteId, "RATE-2026-V1", UUID.randomUUID(), null,
                "ZONE_1", "ZONE_2", 30, 20, 15,
                new BigDecimal("2.50"), new BigDecimal("1.80"), new BigDecimal("2.50"),
                SizeCategory.MEDIUM, 35000L, 15000L, 50000L, "LKR",
                "customer-a", null, false,
                FIXED_NOW, FIXED_NOW.plusSeconds(900)
        );
        QuoteEntity entity = QuoteEntity.fromDomain(snapshot);
        when(quoteRepository.findById(quoteId)).thenReturn(Optional.of(entity));

        CallerContext callerB = CallerContext.authenticated("customer-b", null, Set.of("CUSTOMER"));
        assertThatThrownBy(() -> quoteService.getQuote(quoteId, callerB))
                .isInstanceOf(QuoteNotFoundException.class)
                .hasMessageContaining(quoteId.toString());
    }

    @Test
    @DisplayName("Historical quote preserves original snapshot amounts when new rate version is active")
    void historicalQuoteSnapshotImmutability() {
        UUID quoteId = UUID.randomUUID();
        // Quote created under V1
        QuoteSnapshot originalV1Snapshot = new QuoteSnapshot(
                quoteId, "RATE-2026-V1", UUID.randomUUID(), null,
                "ZONE_1", "ZONE_2", 30, 20, 15,
                new BigDecimal("2.50"), new BigDecimal("1.80"), new BigDecimal("2.50"),
                SizeCategory.MEDIUM, 35000L, 15000L, 50000L, "LKR",
                null, null, true,
                FIXED_NOW, FIXED_NOW.plusSeconds(900)
        );
        QuoteEntity entity = QuoteEntity.fromDomain(originalV1Snapshot);
        when(quoteRepository.findById(quoteId)).thenReturn(Optional.of(entity));

        QuoteResponse retrieved = quoteService.getQuote(quoteId, CallerContext.anonymous());

        // Verifies original values are completely unchanged
        assertThat(retrieved.rateVersion()).isEqualTo("RATE-2026-V1");
        assertThat(retrieved.baseFeeMinorUnits()).isEqualTo(35000L);
        assertThat(retrieved.weightSurchargeMinorUnits()).isEqualTo(15000L);
        assertThat(retrieved.totalAmountMinorUnits()).isEqualTo(50000L);
        assertThat(retrieved.currency()).isEqualTo("LKR");
    }

    @Test
    void futureOrExpiredActiveCardCannotIssueNewQuotes() {
        QuoteRequest request = new QuoteRequest("ZONE_1", "ZONE_2", new BigDecimal("2.50"), 30, 20, 15);
        when(calculationService.calculatePrice(any())).thenReturn(new PricingCalculation(
                "RATE-2026-V1", "LKR", "ZONE_1", "ZONE_2", new BigDecimal("2.50"),
                new BigDecimal("1.80"), new BigDecimal("2.50"), SizeCategory.MEDIUM,
                Money.ofMinorUnits(35000), Money.ofMinorUnits(7500), Money.ofMinorUnits(42500), UUID.randomUUID()));
        RateVersionEntity future = new RateVersionEntity(UUID.randomUUID(), "RATE-2026-V1", "LKR",
                RateVersionStatus.ACTIVE, FIXED_NOW.plusSeconds(1), "Future");
        RateVersionEntity ended = new RateVersionEntity(UUID.randomUUID(), "RATE-2026-V1", "LKR",
                RateVersionStatus.ACTIVE, FIXED_NOW.minusSeconds(60), "Ended");
        ended.setEffectiveTo(FIXED_NOW);
        for (RateVersionEntity card : java.util.List.of(future, ended)) {
            when(rateVersionRepository.findByVersionCode("RATE-2026-V1")).thenReturn(Optional.of(card));
            assertThatThrownBy(() -> quoteService.createQuote(request, CallerContext.anonymous()))
                    .isInstanceOf(com.swiftdrop.pricing.error.RateVersionNotFoundException.class);
        }
        org.mockito.Mockito.verifyNoInteractions(quoteRepository);
    }

    @Test
    void issuedAndStoredTimestampsUsePostgresMicrosecondPrecision() {
        Clock preciseClock = Clock.fixed(FIXED_NOW.plusNanos(123456789), ZoneOffset.UTC);
        QuoteApplicationService service = new QuoteApplicationService(calculationService, rateVersionRepository,
                quoteRepository, ownershipPolicy, preciseClock, 900L,
                org.mockito.Mockito.mock(com.swiftdrop.pricing.persistence.PricingConfigurationLock.class));
        when(calculationService.calculatePrice(any())).thenReturn(new PricingCalculation(
                "RATE-2026-V1", "LKR", "ZONE_1", "ZONE_2", new BigDecimal("2.50"),
                new BigDecimal("1.80"), new BigDecimal("2.50"), SizeCategory.MEDIUM,
                Money.ofMinorUnits(35000), Money.ofMinorUnits(7500), Money.ofMinorUnits(42500), UUID.randomUUID()));
        when(rateVersionRepository.findByVersionCode("RATE-2026-V1")).thenReturn(Optional.of(
                new RateVersionEntity(UUID.randomUUID(), "RATE-2026-V1", "LKR", RateVersionStatus.ACTIVE, FIXED_NOW, "Active")));
        QuoteResponse issued = service.createQuote(new QuoteRequest("ZONE_1", "ZONE_2", new BigDecimal("2.50"),
                30, 20, 15), CallerContext.anonymous());
        ArgumentCaptor<QuoteEntity> saved = ArgumentCaptor.forClass(QuoteEntity.class);
        verify(quoteRepository).saveAndFlush(saved.capture());
        when(quoteRepository.findById(issued.quoteId())).thenReturn(Optional.of(saved.getValue()));

        assertThat(issued.createdAt()).isEqualTo(FIXED_NOW.plusNanos(123456000));
        assertThat(issued.expiresAt()).isEqualTo(issued.createdAt().plusSeconds(900));
        assertThat(saved.getValue().toDomain().getCreatedAt()).isEqualTo(issued.createdAt());
        assertThat(service.getQuote(issued.quoteId(), CallerContext.anonymous())).isEqualTo(issued);
    }
}
