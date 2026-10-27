package com.swiftdrop.pricing.application;

import com.swiftdrop.pricing.api.dto.QuoteRequest;
import com.swiftdrop.pricing.api.dto.QuoteResponse;
import com.swiftdrop.pricing.domain.RateVersionStatus;
import com.swiftdrop.pricing.domain.SizeCategory;
import com.swiftdrop.pricing.domain.TieredWeightPricingStrategy;
import com.swiftdrop.pricing.persistence.*;
import com.swiftdrop.pricing.security.CallerContext;
import com.swiftdrop.pricing.security.QuoteOwnershipPolicy;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * In-process regression of real calculation/snapshot services with mocked storage.
 * The active-card switch is a fixture, not an implemented publish transaction.
 * This test deliberately makes no assertion about gRPC supersession eligibility.
 */
class HistoricalQuoteIsolationTest {

    @Test
    void switchingActiveCardChangesNewQuotesAndPreservesHistoricalSnapshots() {
        ZoneRepository zones = mock(ZoneRepository.class);
        RateVersionRepository versions = mock(RateVersionRepository.class);
        RateRuleRepository rules = mock(RateRuleRepository.class);
        QuoteRepository quotes = mock(QuoteRepository.class);
        Instant issuedAt = Instant.parse("2026-10-27T10:00:00Z");
        Clock clock = Clock.fixed(issuedAt, ZoneOffset.UTC);

        RateVersionEntity v1 = new RateVersionEntity(UUID.randomUUID(), "RATE-V1", "LKR",
                RateVersionStatus.ACTIVE, issuedAt.minusSeconds(3600), "V1");
        RateVersionEntity v2 = new RateVersionEntity(UUID.randomUUID(), "RATE-V2", "LKR",
                RateVersionStatus.ACTIVE, issuedAt, "V2");
        when(zones.findByZoneCode("ZONE_1")).thenReturn(Optional.of(
                new ZoneEntity("ZONE_1", "Origin", "", 24, true)));
        when(zones.findByZoneCode("ZONE_2")).thenReturn(Optional.of(
                new ZoneEntity("ZONE_2", "Destination", "", 48, true)));
        when(versions.findByVersionCode("RATE-V1")).thenReturn(Optional.of(v1));
        when(versions.findByVersionCode("RATE-V2")).thenReturn(Optional.of(v2));
        when(versions.findFirstByCurrencyAndStatus("LKR", RateVersionStatus.ACTIVE))
                .thenReturn(Optional.of(v1), Optional.of(v2));
        when(rules.findByRateVersionIdAndPickupZoneCodeAndDropoffZoneCode(v1.getId(), "ZONE_1", "ZONE_2"))
                .thenReturn(List.of(rule(v1.getId(), 35000)));
        when(rules.findByRateVersionIdAndPickupZoneCodeAndDropoffZoneCode(v2.getId(), "ZONE_1", "ZONE_2"))
                .thenReturn(List.of(rule(v2.getId(), 45000)));
        Map<UUID, QuoteEntity> stored = new HashMap<>();
        when(quotes.saveAndFlush(any(QuoteEntity.class))).thenAnswer(invocation -> {
            QuoteEntity entity = invocation.getArgument(0);
            stored.put(entity.getId(), entity);
            return entity;
        });
        when(quotes.findById(any(UUID.class))).thenAnswer(invocation -> Optional.ofNullable(stored.get(invocation.getArgument(0))));
        QuoteApplicationService service = new QuoteApplicationService(
                new PricingCalculationService(zones, versions, rules, new TieredWeightPricingStrategy()),
                versions, quotes, new QuoteOwnershipPolicy(), clock, 900, mock(PricingConfigurationLock.class));
        CallerContext customer = CallerContext.authenticated("customer-123", null, Set.of("CUSTOMER"));
        QuoteRequest input = new QuoteRequest("ZONE_1", "ZONE_2", new BigDecimal("2.50"), 30, 20, 15);

        QuoteResponse first = service.createQuote(input, customer);
        QuoteResponse second = service.createQuote(input, customer);
        QuoteResponse historical = service.getQuote(first.quoteId(), customer);

        assertThat(first.rateVersion()).isEqualTo("RATE-V1");
        assertThat(first.totalAmountMinorUnits()).isEqualTo(42500);
        assertThat(second.rateVersion()).isEqualTo("RATE-V2");
        assertThat(second.totalAmountMinorUnits()).isEqualTo(52500);
        assertThat(second.quoteId()).isNotEqualTo(first.quoteId());
        assertThat(historical).isEqualTo(first);
        assertThat(historical.currency()).isEqualTo("LKR");
        assertThat(historical.expiresAt()).isEqualTo(issuedAt.plusSeconds(900));
        assertThat(stored.get(first.quoteId()).toDomain().getRateVersionId()).isEqualTo(v1.getId());
        assertThat(stored.get(second.quoteId()).toDomain().getRateVersionId()).isEqualTo(v2.getId());
        verify(quotes, times(2)).saveAndFlush(any(QuoteEntity.class));
        verify(quotes).findById(first.quoteId());
        verifyNoMoreInteractions(quotes);
        verify(versions, never()).save(any());
        verify(rules, never()).save(any());
    }

    private RateRuleEntity rule(UUID versionId, long baseFee) {
        return new RateRuleEntity(UUID.randomUUID(), versionId, "ZONE_1", "ZONE_2", SizeCategory.MEDIUM,
                new BigDecimal("0.01"), new BigDecimal("5.00"), baseFee, 5000, BigDecimal.ONE);
    }
}
