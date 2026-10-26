package com.swiftdrop.pricing.application;

import com.swiftdrop.pricing.domain.Money;
import com.swiftdrop.pricing.domain.PricingCalculation;
import com.swiftdrop.pricing.domain.RateVersionStatus;
import com.swiftdrop.pricing.domain.SizeCategory;
import com.swiftdrop.pricing.domain.TieredWeightPricingStrategy;
import com.swiftdrop.pricing.error.InactiveZoneException;
import com.swiftdrop.pricing.error.RateVersionNotFoundException;
import com.swiftdrop.pricing.error.ZoneNotFoundException;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PricingCalculationServiceTest {

    @Mock
    private ZoneRepository zoneRepository;

    @Mock
    private RateVersionRepository rateVersionRepository;

    @Mock
    private RateRuleRepository rateRuleRepository;

    private PricingCalculationService calculationService;

    private ZoneEntity zone1Entity;
    private ZoneEntity zone2Entity;
    private ZoneEntity inactiveZoneEntity;
    private RateVersionEntity activeVersionV1;
    private RateVersionEntity supersededVersionV0;
    private RateRuleEntity v1Rule;
    private RateRuleEntity v0Rule;

    @BeforeEach
    void setUp() {
        calculationService = new PricingCalculationService(
                zoneRepository,
                rateVersionRepository,
                rateRuleRepository,
                new TieredWeightPricingStrategy()
        );

        zone1Entity = new ZoneEntity("ZONE_1", "Colombo", "Intra-city", 24, true);
        zone2Entity = new ZoneEntity("ZONE_2", "Suburbs", "Regional", 48, true);
        inactiveZoneEntity = new ZoneEntity("ZONE_INACTIVE", "Closed", "Closed", 24, false);

        activeVersionV1 = new RateVersionEntity(
                UUID.randomUUID(), "RATE-2026-V1", "LKR", RateVersionStatus.ACTIVE, Instant.now(), "2026 active");
        supersededVersionV0 = new RateVersionEntity(
                UUID.randomUUID(), "RATE-2025-V0", "LKR", RateVersionStatus.SUPERSEDED, Instant.now().minusSeconds(10000), "2025 old");

        v1Rule = new RateRuleEntity(
                activeVersionV1.getId(), "ZONE_1", "ZONE_2", SizeCategory.MEDIUM,
                new BigDecimal("0.01"), new BigDecimal("5.00"),
                50000L, 6000L, new BigDecimal("1.00"));

        v0Rule = new RateRuleEntity(
                supersededVersionV0.getId(), "ZONE_1", "ZONE_2", SizeCategory.MEDIUM,
                new BigDecimal("0.01"), new BigDecimal("5.00"),
                40000L, 5000L, new BigDecimal("1.00"));
    }

    @Test
    @DisplayName("Should calculate price using the active rate version by default")
    void shouldCalculatePriceUsingActiveRateVersion() {
        when(zoneRepository.findByZoneCode("ZONE_1")).thenReturn(Optional.of(zone1Entity));
        when(zoneRepository.findByZoneCode("ZONE_2")).thenReturn(Optional.of(zone2Entity));
        when(rateVersionRepository.findFirstByCurrencyAndStatus("LKR", RateVersionStatus.ACTIVE))
                .thenReturn(Optional.of(activeVersionV1));
        when(rateRuleRepository.findByRateVersionIdAndPickupZoneCodeAndDropoffZoneCode(
                activeVersionV1.getId(), "ZONE_1", "ZONE_2"))
                .thenReturn(List.of(v1Rule));

        CalculationCommand command = CalculationCommand.of(
                "ZONE_1", "ZONE_2", 30, 20, 15, new BigDecimal("2.50"));

        PricingCalculation calculation = calculationService.calculatePrice(command);

        assertThat(calculation.getRateVersion()).isEqualTo("RATE-2026-V1");
        assertThat(calculation.getCurrency()).isEqualTo("LKR");
        assertThat(calculation.getBaseFeeMinorUnits()).isEqualTo(50000L); // 500.00 LKR
        // 2.50 - 1.00 = 1.50 kg excess * 6000 = 9000 minor units (90.00 LKR)
        assertThat(calculation.getWeightSurchargeMinorUnits()).isEqualTo(9000L);
        assertThat(calculation.getTotalAmountMinorUnits()).isEqualTo(59000L); // 590.00 LKR
    }

    @Test
    @DisplayName("Should calculate price using a historical/explicit rate version without mutating or affected by active version")
    void shouldCalculatePriceUsingExplicitHistoricalVersion() {
        when(zoneRepository.findByZoneCode("ZONE_1")).thenReturn(Optional.of(zone1Entity));
        when(zoneRepository.findByZoneCode("ZONE_2")).thenReturn(Optional.of(zone2Entity));
        when(rateVersionRepository.findByVersionCode("RATE-2025-V0"))
                .thenReturn(Optional.of(supersededVersionV0));
        when(rateRuleRepository.findByRateVersionIdAndPickupZoneCodeAndDropoffZoneCode(
                supersededVersionV0.getId(), "ZONE_1", "ZONE_2"))
                .thenReturn(List.of(v0Rule));

        CalculationCommand command = CalculationCommand.of(
                "ZONE_1", "ZONE_2", 30, 20, 15, new BigDecimal("2.50"), "RATE-2025-V0");

        PricingCalculation calculation = calculationService.calculatePrice(command);

        assertThat(calculation.getRateVersion()).isEqualTo("RATE-2025-V0");
        assertThat(calculation.getBaseFeeMinorUnits()).isEqualTo(40000L); // 400.00 LKR in V0
        // 1.50 * 5000 = 7500 minor units
        assertThat(calculation.getWeightSurchargeMinorUnits()).isEqualTo(7500L);
        assertThat(calculation.getTotalAmountMinorUnits()).isEqualTo(47500L); // 475.00 LKR in V0
    }

    @Test
    @DisplayName("Should throw ZoneNotFoundException when pickup or dropoff zone does not exist")
    void shouldThrowWhenZoneNotFound() {
        when(zoneRepository.findByZoneCode("UNKNOWN_ZONE")).thenReturn(Optional.empty());

        CalculationCommand command = CalculationCommand.of(
                "UNKNOWN_ZONE", "ZONE_2", 20, 20, 10, new BigDecimal("1.00"));

        assertThatThrownBy(() -> calculationService.calculatePrice(command))
                .isInstanceOf(ZoneNotFoundException.class)
                .hasMessageContaining("UNKNOWN_ZONE");
    }

    @Test
    @DisplayName("Should throw InactiveZoneException when pickup or dropoff zone is inactive")
    void shouldThrowWhenZoneInactive() {
        when(zoneRepository.findByZoneCode("ZONE_INACTIVE")).thenReturn(Optional.of(inactiveZoneEntity));

        CalculationCommand command = CalculationCommand.of(
                "ZONE_INACTIVE", "ZONE_2", 20, 20, 10, new BigDecimal("1.00"));

        assertThatThrownBy(() -> calculationService.calculatePrice(command))
                .isInstanceOf(InactiveZoneException.class)
                .hasMessageContaining("ZONE_INACTIVE");
    }

    @Test
    @DisplayName("Should throw RateVersionNotFoundException when specified rate version does not exist")
    void shouldThrowWhenRateVersionNotFound() {
        when(zoneRepository.findByZoneCode("ZONE_1")).thenReturn(Optional.of(zone1Entity));
        when(zoneRepository.findByZoneCode("ZONE_2")).thenReturn(Optional.of(zone2Entity));
        when(rateVersionRepository.findByVersionCode("NON_EXISTENT_VERSION")).thenReturn(Optional.empty());

        CalculationCommand command = CalculationCommand.of(
                "ZONE_1", "ZONE_2", 20, 20, 10, new BigDecimal("1.00"), "NON_EXISTENT_VERSION");

        assertThatThrownBy(() -> calculationService.calculatePrice(command))
                .isInstanceOf(RateVersionNotFoundException.class)
                .hasMessageContaining("NON_EXISTENT_VERSION");
    }
}
