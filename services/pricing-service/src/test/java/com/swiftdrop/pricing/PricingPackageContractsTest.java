package com.swiftdrop.pricing;

import com.swiftdrop.pricing.application.CalculationCommand;
import com.swiftdrop.pricing.domain.Money;
import com.swiftdrop.pricing.domain.ParcelMeasurements;
import com.swiftdrop.pricing.domain.PricingCalculation;
import com.swiftdrop.pricing.domain.PricingZone;
import com.swiftdrop.pricing.domain.RateRule;
import com.swiftdrop.pricing.domain.RateVersion;
import com.swiftdrop.pricing.domain.RateVersionStatus;
import com.swiftdrop.pricing.domain.SizeCategory;
import com.swiftdrop.pricing.error.AmbiguousRateRuleException;
import com.swiftdrop.pricing.error.CurrencyMismatchException;
import com.swiftdrop.pricing.error.InactiveZoneException;
import com.swiftdrop.pricing.error.InvalidMeasurementsException;
import com.swiftdrop.pricing.error.PricingDomainException;
import com.swiftdrop.pricing.error.RateRuleNotFoundException;
import com.swiftdrop.pricing.error.RateVersionImmutableException;
import com.swiftdrop.pricing.error.RateVersionNotFoundException;
import com.swiftdrop.pricing.error.ZoneNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PricingPackageContractsTest {

    @Test
    @DisplayName("PricingServiceApplication declares independent SpringBootApplication entry point")
    void declaresIndependentBootApplication() {
        assertThat(PricingServiceApplication.class.isAnnotationPresent(SpringBootApplication.class)).isTrue();
    }

    @Test
    @DisplayName("Verify CalculationCommand construction and defaults")
    void testCalculationCommand() {
        CalculationCommand cmd = CalculationCommand.of(
                "ZONE_1", "ZONE_2", 30, 20, 15, new BigDecimal("2.50"));

        assertThat(cmd.pickupZoneCode()).isEqualTo("ZONE_1");
        assertThat(cmd.dropoffZoneCode()).isEqualTo("ZONE_2");
        assertThat(cmd.lengthCm()).isEqualTo(30);
        assertThat(cmd.widthCm()).isEqualTo(20);
        assertThat(cmd.heightCm()).isEqualTo(15);
        assertThat(cmd.physicalWeightKg()).isEqualByComparingTo("2.50");
        assertThat(cmd.currency()).isEqualTo("LKR");
        assertThat(cmd.rateVersionCode()).isNull();

        ParcelMeasurements measurements = cmd.toMeasurements();
        assertThat(measurements.getChargeableWeightKg()).isEqualByComparingTo("2.50");
        assertThat(measurements.getSizeCategory()).isEqualTo(SizeCategory.MEDIUM);
    }

    @Test
    @DisplayName("Verify PricingCalculation fields and minor units accessors")
    void testPricingCalculation() {
        UUID ruleId = UUID.randomUUID();
        PricingCalculation calc = new PricingCalculation(
                "RATE-2026-V1",
                "LKR",
                "ZONE_1",
                "ZONE_2",
                new BigDecimal("2.50"),
                new BigDecimal("1.80"),
                new BigDecimal("2.50"),
                SizeCategory.MEDIUM,
                Money.ofMinorUnits(50000L, "LKR"),
                Money.ofMinorUnits(9000L, "LKR"),
                Money.ofMinorUnits(59000L, "LKR"),
                ruleId
        );

        assertThat(calc.getRateVersion()).isEqualTo("RATE-2026-V1");
        assertThat(calc.getCurrency()).isEqualTo("LKR");
        assertThat(calc.getPickupZone()).isEqualTo("ZONE_1");
        assertThat(calc.getDropoffZone()).isEqualTo("ZONE_2");
        assertThat(calc.getPhysicalWeightKg()).isEqualByComparingTo("2.50");
        assertThat(calc.getVolumetricWeightKg()).isEqualByComparingTo("1.80");
        assertThat(calc.getChargeableWeightKg()).isEqualByComparingTo("2.50");
        assertThat(calc.getSizeCategory()).isEqualTo(SizeCategory.MEDIUM);
        assertThat(calc.getBaseFeeMinorUnits()).isEqualTo(50000L);
        assertThat(calc.getWeightSurchargeMinorUnits()).isEqualTo(9000L);
        assertThat(calc.getTotalAmountMinorUnits()).isEqualTo(59000L);
        assertThat(calc.getEvaluatedRuleId()).isEqualTo(ruleId);
    }

    @Test
    @DisplayName("Verify PricingZone domain invariants")
    void testPricingZone() {
        UUID id = UUID.randomUUID();
        PricingZone zone = new PricingZone(id, "ZONE_1", "Colombo", "Intra-city", 24, true);

        assertThat(zone.getId()).isEqualTo(id);
        assertThat(zone.getZoneCode()).isEqualTo("ZONE_1");
        assertThat(zone.getName()).isEqualTo("Colombo");
        assertThat(zone.getDescription()).isEqualTo("Intra-city");
        assertThat(zone.getStandardTransitHours()).isEqualTo(24);
        assertThat(zone.isActive()).isTrue();

        assertThatThrownBy(() -> new PricingZone(id, "ZONE_1", "Colombo", "Desc", 0, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Verify RateRule constructor validation and surcharge logic")
    void testRateRuleInvariants() {
        UUID ruleId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();

        RateRule rule = new RateRule(
                ruleId,
                versionId,
                "ZONE_1",
                "ZONE_2",
                SizeCategory.MEDIUM,
                new BigDecimal("0.01"),
                new BigDecimal("5.00"),
                Money.ofMinorUnits(35000L, "LKR"),
                Money.ofMinorUnits(5000L, "LKR"),
                new BigDecimal("1.00")
        );

        assertThat(rule.matchesWeight(new BigDecimal("2.50"))).isTrue();
        assertThat(rule.matchesWeight(new BigDecimal("0.00"))).isFalse();
        assertThat(rule.matchesWeight(new BigDecimal("5.01"))).isFalse();

        // 2.50 - 1.00 = 1.50 * 5000 = 7500
        Money surcharge = rule.calculateWeightSurcharge(new BigDecimal("2.50"));
        assertThat(surcharge.getAmountMinorUnits()).isEqualTo(7500L);

        Money total = rule.calculateTotalFee(new BigDecimal("2.50"));
        assertThat(total.getAmountMinorUnits()).isEqualTo(42500L);
    }

    @Test
    @DisplayName("Verify error hierarchy and error codes")
    void testErrorCodes() {
        assertThat(new InvalidMeasurementsException("dim error").getErrorCode()).isEqualTo("INVALID_MEASUREMENTS");
        assertThat(new ZoneNotFoundException("ZONE_9").getErrorCode()).isEqualTo("ZONE_NOT_FOUND");
        assertThat(new InactiveZoneException("ZONE_X").getErrorCode()).isEqualTo("ZONE_INACTIVE");
        assertThat(new RateVersionNotFoundException("V9").getErrorCode()).isEqualTo("RATE_VERSION_NOT_FOUND");
        assertThat(new RateRuleNotFoundException("rule missing").getErrorCode()).isEqualTo("RATE_RULE_NOT_FOUND");
        assertThat(new AmbiguousRateRuleException("ambiguous").getErrorCode()).isEqualTo("AMBIGUOUS_RATE_RULE");
        assertThat(new RateVersionImmutableException("immutable").getErrorCode()).isEqualTo("RATE_VERSION_IMMUTABLE");
        assertThat(new CurrencyMismatchException("currency error").getErrorCode()).isEqualTo("CURRENCY_MISMATCH");
    }
}
