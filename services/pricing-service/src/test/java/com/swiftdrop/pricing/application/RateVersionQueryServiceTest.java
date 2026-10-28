package com.swiftdrop.pricing.application;

import com.swiftdrop.pricing.domain.PricingZone;
import com.swiftdrop.pricing.domain.RateRule;
import com.swiftdrop.pricing.domain.RateVersion;
import com.swiftdrop.pricing.domain.RateVersionStatus;
import com.swiftdrop.pricing.domain.SizeCategory;
import com.swiftdrop.pricing.error.RateVersionNotFoundException;
import com.swiftdrop.pricing.persistence.RateRuleEntity;
import com.swiftdrop.pricing.persistence.RateRuleRepository;
import com.swiftdrop.pricing.persistence.RateVersionEntity;
import com.swiftdrop.pricing.persistence.RateVersionRepository;
import com.swiftdrop.pricing.persistence.ZoneEntity;
import com.swiftdrop.pricing.persistence.ZoneRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RateVersionQueryServiceTest {

    @Mock
    private ZoneRepository zoneRepository;

    @Mock
    private RateVersionRepository rateVersionRepository;

    @Mock
    private RateRuleRepository rateRuleRepository;

    private RateVersionQueryService queryService;

    @BeforeEach
    void setUp() {
        queryService = new RateVersionQueryService(zoneRepository, rateVersionRepository, rateRuleRepository);
    }

    @Test
    @DisplayName("Should return active zones ordered by zone code")
    void shouldReturnActiveZones() {
        ZoneEntity z1 = new ZoneEntity("ZONE_1", "Colombo", "Desc 1", 24, true);
        ZoneEntity z2 = new ZoneEntity("ZONE_2", "Suburbs", "Desc 2", 48, true);

        when(zoneRepository.findByActiveTrueOrderByZoneCodeAsc()).thenReturn(List.of(z1, z2));

        List<PricingZone> zones = queryService.getActiveZones();

        assertThat(zones).hasSize(2);
        assertThat(zones.get(0).getZoneCode()).isEqualTo("ZONE_1");
        assertThat(zones.get(1).getZoneCode()).isEqualTo("ZONE_2");
    }

    @Test
    @DisplayName("Should return active rate version for given currency")
    void shouldReturnActiveRateVersion() {
        RateVersionEntity entity = new RateVersionEntity(
                "RATE-2026-V1", "LKR", RateVersionStatus.ACTIVE, Instant.now(), "Active");

        when(rateVersionRepository.findFirstByCurrencyAndStatus("LKR", RateVersionStatus.ACTIVE))
                .thenReturn(Optional.of(entity));

        RateVersion active = queryService.getActiveRateVersion("LKR");

        assertThat(active.getVersionCode()).isEqualTo("RATE-2026-V1");
        assertThat(active.getCurrency()).isEqualTo("LKR");
        assertThat(active.getStatus()).isEqualTo(RateVersionStatus.ACTIVE);
    }

    @Test
    @DisplayName("Should throw RateVersionNotFoundException if no active rate version exists")
    void shouldThrowIfNoActiveRateVersion() {
        when(rateVersionRepository.findFirstByCurrencyAndStatus("USD", RateVersionStatus.ACTIVE))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> queryService.getActiveRateVersion("USD"))
                .isInstanceOf(RateVersionNotFoundException.class)
                .hasMessageContaining("USD");
    }

    @Test
    @DisplayName("Should return rate rules for a specific version code")
    void shouldReturnRateRulesForVersion() {
        RateVersionEntity versionEntity = new RateVersionEntity(
                UUID.randomUUID(), "RATE-2026-V1", "LKR", RateVersionStatus.ACTIVE, Instant.now(), "Active");
        UUID versionId = versionEntity.getId();

        RateRuleEntity ruleEntity = new RateRuleEntity(
                versionId, "ZONE_1", "ZONE_1", SizeCategory.SMALL,
                new BigDecimal("0.01"), new BigDecimal("1.00"),
                25000L, 0L, BigDecimal.ZERO
        );

        when(rateVersionRepository.findByVersionCode("RATE-2026-V1")).thenReturn(Optional.of(versionEntity));
        when(rateRuleRepository.findByRateVersionId(versionId)).thenReturn(List.of(ruleEntity));

        List<RateRule> rules = queryService.getRateRulesForVersion("RATE-2026-V1");

        assertThat(rules).hasSize(1);
        assertThat(rules.getFirst().getPickupZoneCode()).isEqualTo("ZONE_1");
        assertThat(rules.getFirst().getBaseFee().getAmountMinorUnits()).isEqualTo(25000L);
    }
}
