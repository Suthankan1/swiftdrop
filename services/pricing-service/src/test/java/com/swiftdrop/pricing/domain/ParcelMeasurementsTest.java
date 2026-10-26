package com.swiftdrop.pricing.domain;

import com.swiftdrop.pricing.error.InvalidMeasurementsException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ParcelMeasurementsTest {

    @Test
    @DisplayName("Should create valid ParcelMeasurements at minimum boundaries (0.01 kg, 1x1x1 cm)")
    void shouldCreateMeasurementsAtMinimumBoundaries() {
        ParcelMeasurements measurements = ParcelMeasurements.of(1, 1, 1, 0.01);

        assertThat(measurements.getLengthCm()).isEqualTo(1);
        assertThat(measurements.getWidthCm()).isEqualTo(1);
        assertThat(measurements.getHeightCm()).isEqualTo(1);
        assertThat(measurements.getPhysicalWeightKg()).isEqualByComparingTo("0.01");
        assertThat(measurements.getChargeableWeightKg()).isEqualByComparingTo("0.01");
        assertThat(measurements.getSizeCategory()).isEqualTo(SizeCategory.SMALL);
    }

    @Test
    @DisplayName("Should create valid ParcelMeasurements at maximum boundaries (30.00 kg, within max girth)")
    void shouldCreateMeasurementsAtMaximumBoundaries() {
        // L=100, W=50, H=50 -> longest=100, girth=2*(50+50)=200 -> combined=300 cm (valid limit)
        // volume = 100 * 50 * 50 = 250,000 / 5000 = 50.00 kg volumetric!
        // That volumetric would exceed 30kg max. So physical dimensions must yield <= 30kg chargeable weight:
        // L=60, W=50, H=50 -> 150,000 / 5000 = 30.00 kg volumetric weight
        ParcelMeasurements measurements = ParcelMeasurements.of(60, 50, 50, 30.00);

        assertThat(measurements.getPhysicalWeightKg()).isEqualByComparingTo("30.00");
        assertThat(measurements.getVolumetricWeightKg()).isEqualByComparingTo("30.00");
        assertThat(measurements.getChargeableWeightKg()).isEqualByComparingTo("30.00");
        assertThat(measurements.getSizeCategory()).isEqualTo(SizeCategory.OVERSIZED);
    }

    @Test
    @DisplayName("Should compute correct volumetric weight: (L * W * H) / 5000")
    void shouldComputeCorrectVolumetricWeight() {
        // 30 * 20 * 15 = 9000 / 5000 = 1.80 kg
        ParcelMeasurements measurements = ParcelMeasurements.of(30, 20, 15, 1.20);

        assertThat(measurements.getPhysicalWeightKg()).isEqualByComparingTo("1.20");
        assertThat(measurements.getVolumetricWeightKg()).isEqualByComparingTo("1.80");
        assertThat(measurements.getChargeableWeightKg()).isEqualByComparingTo("1.80");
        assertThat(measurements.getSizeCategory()).isEqualTo(SizeCategory.MEDIUM);
    }

    @Test
    @DisplayName("Should pick physical weight when physical weight exceeds volumetric weight")
    void shouldPickPhysicalWeightWhenLarger() {
        // 30 * 20 * 15 = 1.80 kg volumetric, physical = 2.50 kg
        ParcelMeasurements measurements = ParcelMeasurements.of(30, 20, 15, 2.50);

        assertThat(measurements.getPhysicalWeightKg()).isEqualByComparingTo("2.50");
        assertThat(measurements.getVolumetricWeightKg()).isEqualByComparingTo("1.80");
        assertThat(measurements.getChargeableWeightKg()).isEqualByComparingTo("2.50");
        assertThat(measurements.getSizeCategory()).isEqualTo(SizeCategory.MEDIUM);
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.00, -0.50, 30.01, 100.00})
    @DisplayName("Should reject physical weight outside [0.01, 30.00] kg bounds")
    void shouldRejectInvalidPhysicalWeight(double weight) {
        assertThatThrownBy(() -> ParcelMeasurements.of(20, 20, 20, weight))
                .isInstanceOf(InvalidMeasurementsException.class);
    }

    @Test
    @DisplayName("Should reject null physical weight")
    void shouldRejectNullPhysicalWeight() {
        assertThatThrownBy(() -> ParcelMeasurements.of(20, 20, 20, (BigDecimal) null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Should reject dimensions below 1 cm or above 150 cm")
    void shouldRejectDimensionOutOfBounds() {
        assertThatThrownBy(() -> ParcelMeasurements.of(0, 20, 20, 1.0))
                .isInstanceOf(InvalidMeasurementsException.class)
                .hasMessageContaining("Length");

        assertThatThrownBy(() -> ParcelMeasurements.of(151, 20, 20, 1.0))
                .isInstanceOf(InvalidMeasurementsException.class)
                .hasMessageContaining("Length");

        assertThatThrownBy(() -> ParcelMeasurements.of(20, -1, 20, 1.0))
                .isInstanceOf(InvalidMeasurementsException.class)
                .hasMessageContaining("Width");

        assertThatThrownBy(() -> ParcelMeasurements.of(20, 20, 160, 1.0))
                .isInstanceOf(InvalidMeasurementsException.class)
                .hasMessageContaining("Height");
    }

    @Test
    @DisplayName("Should reject parcels where length + girth exceeds 300 cm")
    void shouldRejectExcessiveLengthPlusGirth() {
        // L=120, W=50, H=50 -> longest=120, girth=2*(50+50)=200 -> combined=320 cm (> 300 cm)
        assertThatThrownBy(() -> ParcelMeasurements.of(120, 50, 50, 10.0))
                .isInstanceOf(InvalidMeasurementsException.class)
                .hasMessageContaining("Combined length and girth");
    }

    @Test
    @DisplayName("Should classify size categories correctly")
    void shouldClassifyCategoriesCorrectly() {
        // SMALL: max dim <= 25 cm, weight <= 1.00 kg
        ParcelMeasurements small = ParcelMeasurements.of(25, 20, 10, 0.90);
        assertThat(small.getSizeCategory()).isEqualTo(SizeCategory.SMALL);

        // MEDIUM: max dim <= 50 cm, weight <= 5.00 kg
        ParcelMeasurements medium = ParcelMeasurements.of(35, 20, 15, 2.50);
        assertThat(medium.getSizeCategory()).isEqualTo(SizeCategory.MEDIUM);

        // LARGE: max dim <= 100 cm, weight <= 15.00 kg
        ParcelMeasurements large = ParcelMeasurements.of(80, 40, 20, 10.00);
        assertThat(large.getSizeCategory()).isEqualTo(SizeCategory.LARGE);

        // OVERSIZED: max dim <= 150 cm, weight <= 30.00 kg
        ParcelMeasurements oversized = ParcelMeasurements.of(120, 30, 20, 22.00);
        assertThat(oversized.getSizeCategory()).isEqualTo(SizeCategory.OVERSIZED);
    }
}
