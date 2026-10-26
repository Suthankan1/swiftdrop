package com.swiftdrop.pricing.domain;

import com.swiftdrop.pricing.error.InvalidMeasurementsException;

import java.math.BigDecimal;

/**
 * Standard parcel size categories per DEC-PRC-05.
 *
 * SMALL: max dimension <= 25 cm, chargeable weight <= 1.00 kg
 * MEDIUM: max dimension <= 50 cm, chargeable weight <= 5.00 kg
 * LARGE: max dimension <= 100 cm, chargeable weight <= 15.00 kg
 * OVERSIZED: max dimension <= 150 cm, chargeable weight <= 30.00 kg
 */
public enum SizeCategory {
    SMALL(25, new BigDecimal("1.00")),
    MEDIUM(50, new BigDecimal("5.00")),
    LARGE(100, new BigDecimal("15.00")),
    OVERSIZED(150, new BigDecimal("30.00"));

    private final int maxDimensionCm;
    private final BigDecimal maxWeightKg;

    SizeCategory(int maxDimensionCm, BigDecimal maxWeightKg) {
        this.maxDimensionCm = maxDimensionCm;
        this.maxWeightKg = maxWeightKg;
    }

    public int getMaxDimensionCm() {
        return maxDimensionCm;
    }

    public BigDecimal getMaxWeightKg() {
        return maxWeightKg;
    }

    /**
     * Resolves the smallest qualifying size category for the given dimension and chargeable weight.
     *
     * @param maxDimCm the longest side of the parcel in cm
     * @param chargeableWeightKg the maximum of physical or volumetric weight
     * @return the resolved SizeCategory
     * @throws InvalidMeasurementsException if measurements exceed maximum supported thresholds
     */
    public static SizeCategory classify(int maxDimCm, BigDecimal chargeableWeightKg) {
        if (maxDimCm <= SMALL.maxDimensionCm && chargeableWeightKg.compareTo(SMALL.maxWeightKg) <= 0) {
            return SMALL;
        }
        if (maxDimCm <= MEDIUM.maxDimensionCm && chargeableWeightKg.compareTo(MEDIUM.maxWeightKg) <= 0) {
            return MEDIUM;
        }
        if (maxDimCm <= LARGE.maxDimensionCm && chargeableWeightKg.compareTo(LARGE.maxWeightKg) <= 0) {
            return LARGE;
        }
        if (maxDimCm <= OVERSIZED.maxDimensionCm && chargeableWeightKg.compareTo(OVERSIZED.maxWeightKg) <= 0) {
            return OVERSIZED;
        }
        throw new InvalidMeasurementsException(
                "Parcel exceeds maximum supported thresholds (max dimension <= 150cm, weight <= 30kg): maxDim="
                        + maxDimCm + "cm, weight=" + chargeableWeightKg + "kg");
    }
}
