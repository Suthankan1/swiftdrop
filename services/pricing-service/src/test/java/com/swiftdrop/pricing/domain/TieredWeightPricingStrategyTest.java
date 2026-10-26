package com.swiftdrop.pricing.domain;

import com.swiftdrop.pricing.error.AmbiguousRateRuleException;
import com.swiftdrop.pricing.error.InactiveZoneException;
import com.swiftdrop.pricing.error.RateRuleNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TieredWeightPricingStrategyTest {

    private TieredWeightPricingStrategy strategy;
    private RateVersion activeVersion;
    private PricingZone zone1;
    private PricingZone zone2;
    private PricingZone inactiveZone;

    @BeforeEach
    void setUp() {
        strategy = new TieredWeightPricingStrategy();
        activeVersion = new RateVersion(
                UUID.randomUUID(),
                "RATE-2026-V1",
                "LKR",
                RateVersionStatus.ACTIVE,
                Instant.now(),
                null,
                "Test card",
                Instant.now(),
                Instant.now(),
                null
        );

        zone1 = new PricingZone(UUID.randomUUID(), "ZONE_1", "Colombo", "Intra-city", 24, true);
        zone2 = new PricingZone(UUID.randomUUID(), "ZONE_2", "Suburbs", "Regional", 48, true);
        inactiveZone = new PricingZone(UUID.randomUUID(), "ZONE_X", "Closed", "Closed", 48, false);
    }

    @Test
    @DisplayName("Should deterministically calculate price without surcharge when weight <= threshold")
    void shouldCalculatePriceWithoutSurcharge() {
        RateRule rule = new RateRule(
                UUID.randomUUID(),
                activeVersion.getId(),
                "ZONE_1",
                "ZONE_1",
                SizeCategory.SMALL,
                new BigDecimal("0.01"),
                new BigDecimal("1.00"),
                Money.ofMinorUnits(25000L, "LKR"), // 250.00 LKR
                Money.ofMinorUnits(0L, "LKR"),
                new BigDecimal("1.00")
        );

        ParcelMeasurements measurements = ParcelMeasurements.of(15, 10, 5, 0.80);

        PricingCalculation result = strategy.calculate(
                activeVersion, zone1, zone1, measurements, List.of(rule));

        assertThat(result.getRateVersion()).isEqualTo("RATE-2026-V1");
        assertThat(result.getCurrency()).isEqualTo("LKR");
        assertThat(result.getPickupZone()).isEqualTo("ZONE_1");
        assertThat(result.getDropoffZone()).isEqualTo("ZONE_1");
        assertThat(result.getBaseFeeMinorUnits()).isEqualTo(25000L);
        assertThat(result.getWeightSurchargeMinorUnits()).isEqualTo(0L);
        assertThat(result.getTotalAmountMinorUnits()).isEqualTo(25000L);
    }

    @Test
    @DisplayName("Should deterministically calculate price with surcharge when weight > threshold")
    void shouldCalculatePriceWithSurcharge() {
        // Medium: base fee 35000 (350.00 LKR), surcharge 5000 (50.00 LKR/kg), threshold 1.00 kg
        RateRule rule = new RateRule(
                UUID.randomUUID(),
                activeVersion.getId(),
                "ZONE_1",
                "ZONE_1",
                SizeCategory.MEDIUM,
                new BigDecimal("0.01"),
                new BigDecimal("5.00"),
                Money.ofMinorUnits(35000L, "LKR"),
                Money.ofMinorUnits(5000L, "LKR"),
                new BigDecimal("1.00")
        );

        // 30x20x15 -> volumetric = 1.80 kg, physical = 2.50 kg -> chargeable = 2.50 kg
        // Excess weight = 2.50 - 1.00 = 1.50 kg
        // Surcharge = 5000 * 1.50 = 7500 minor units (75.00 LKR)
        // Total = 35000 + 7500 = 42500 minor units (425.00 LKR)
        ParcelMeasurements measurements = ParcelMeasurements.of(30, 20, 15, 2.50);

        PricingCalculation result1 = strategy.calculate(
                activeVersion, zone1, zone1, measurements, List.of(rule));
        PricingCalculation result2 = strategy.calculate(
                activeVersion, zone1, zone1, measurements, List.of(rule));

        assertThat(result1.getBaseFeeMinorUnits()).isEqualTo(35000L);
        assertThat(result1.getWeightSurchargeMinorUnits()).isEqualTo(7500L);
        assertThat(result1.getTotalAmountMinorUnits()).isEqualTo(42500L);

        // Reproducibility check: identical result
        assertThat(result1.getTotalAmountMinorUnits()).isEqualTo(result2.getTotalAmountMinorUnits());
        assertThat(result1.getBaseFeeMinorUnits()).isEqualTo(result2.getBaseFeeMinorUnits());
        assertThat(result1.getWeightSurchargeMinorUnits()).isEqualTo(result2.getWeightSurchargeMinorUnits());
    }

    @Test
    @DisplayName("Should reject calculation when origin or destination zone is inactive")
    void shouldRejectInactiveZone() {
        ParcelMeasurements measurements = ParcelMeasurements.of(20, 20, 10, 1.50);

        assertThatThrownBy(() -> strategy.calculate(
                activeVersion, inactiveZone, zone1, measurements, List.of()))
                .isInstanceOf(InactiveZoneException.class);

        assertThatThrownBy(() -> strategy.calculate(
                activeVersion, zone1, inactiveZone, measurements, List.of()))
                .isInstanceOf(InactiveZoneException.class);
    }

    @Test
    @DisplayName("Should throw RateRuleNotFoundException when no rule matches")
    void shouldThrowWhenNoRuleMatches() {
        ParcelMeasurements measurements = ParcelMeasurements.of(20, 20, 10, 1.50);

        assertThatThrownBy(() -> strategy.calculate(
                activeVersion, zone1, zone2, measurements, List.of()))
                .isInstanceOf(RateRuleNotFoundException.class)
                .hasMessageContaining("No matching rate rule found");
    }

    @Test
    @DisplayName("Should throw AmbiguousRateRuleException when multiple overlapping rules match")
    void shouldThrowWhenMultipleRulesMatch() {
        RateRule rule1 = new RateRule(
                UUID.randomUUID(), activeVersion.getId(), "ZONE_1", "ZONE_1",
                SizeCategory.SMALL, new BigDecimal("0.01"), new BigDecimal("1.00"),
                Money.ofMinorUnits(20000L, "LKR"), Money.zero("LKR"), BigDecimal.ZERO);

        RateRule rule2 = new RateRule(
                UUID.randomUUID(), activeVersion.getId(), "ZONE_1", "ZONE_1",
                SizeCategory.SMALL, new BigDecimal("0.50"), new BigDecimal("1.00"),
                Money.ofMinorUnits(25000L, "LKR"), Money.zero("LKR"), BigDecimal.ZERO);

        ParcelMeasurements measurements = ParcelMeasurements.of(10, 10, 5, 0.75);

        assertThatThrownBy(() -> strategy.calculate(
                activeVersion, zone1, zone1, measurements, List.of(rule1, rule2)))
                .isInstanceOf(AmbiguousRateRuleException.class)
                .hasMessageContaining("Multiple (2) overlapping rate rules match");
    }
}
