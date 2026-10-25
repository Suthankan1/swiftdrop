package com.swiftdrop.pricing.domain;

import com.swiftdrop.pricing.error.InvalidMeasurementsException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Validated value object representing physical and volumetric parcel measurements.
 *
 * Implements DEC-PRC-04 (Weight limits 0.01 - 30.00 kg, scale 2)
 * and DEC-PRC-05 (Max dimension 150 cm, length + girth <= 300 cm, volumetric divisor 5000).
 */
public final class ParcelMeasurements {

    public static final BigDecimal MIN_WEIGHT_KG = new BigDecimal("0.01");
    public static final BigDecimal MAX_WEIGHT_KG = new BigDecimal("30.00");
    public static final int MIN_DIMENSION_CM = 1;
    public static final int MAX_DIMENSION_CM = 150;
    public static final int MAX_LENGTH_PLUS_GIRTH_CM = 300;
    public static final BigDecimal VOLUMETRIC_DIVISOR = new BigDecimal("5000.0");

    private final int lengthCm;
    private final int widthCm;
    private final int heightCm;
    private final BigDecimal physicalWeightKg;
    private final BigDecimal volumetricWeightKg;
    private final BigDecimal chargeableWeightKg;
    private final SizeCategory sizeCategory;

    public ParcelMeasurements(int lengthCm, int widthCm, int heightCm, BigDecimal physicalWeightKg) {
        validateDimensions(lengthCm, widthCm, heightCm);
        this.lengthCm = lengthCm;
        this.widthCm = widthCm;
        this.heightCm = heightCm;

        this.physicalWeightKg = validateWeight(physicalWeightKg);
        this.volumetricWeightKg = calculateVolumetricWeight(lengthCm, widthCm, heightCm);
        this.chargeableWeightKg = this.physicalWeightKg.max(this.volumetricWeightKg);

        if (this.chargeableWeightKg.compareTo(MAX_WEIGHT_KG) > 0) {
            throw new InvalidMeasurementsException(
                    "Computed chargeable weight (" + this.chargeableWeightKg + " kg) exceeds maximum limit of " + MAX_WEIGHT_KG + " kg");
        }

        int maxDim = Math.max(lengthCm, Math.max(widthCm, heightCm));
        this.sizeCategory = SizeCategory.classify(maxDim, this.chargeableWeightKg);
    }

    public static ParcelMeasurements of(int lengthCm, int widthCm, int heightCm, double physicalWeightKg) {
        return new ParcelMeasurements(lengthCm, widthCm, heightCm, BigDecimal.valueOf(physicalWeightKg).setScale(2, RoundingMode.HALF_UP));
    }

    public static ParcelMeasurements of(int lengthCm, int widthCm, int heightCm, BigDecimal physicalWeightKg) {
        return new ParcelMeasurements(lengthCm, widthCm, heightCm, physicalWeightKg);
    }

    private static void validateDimensions(int length, int width, int height) {
        if (length < MIN_DIMENSION_CM || length > MAX_DIMENSION_CM) {
            throw new InvalidMeasurementsException("Length (" + length + " cm) must be between " + MIN_DIMENSION_CM + " and " + MAX_DIMENSION_CM + " cm");
        }
        if (width < MIN_DIMENSION_CM || width > MAX_DIMENSION_CM) {
            throw new InvalidMeasurementsException("Width (" + width + " cm) must be between " + MIN_DIMENSION_CM + " and " + MAX_DIMENSION_CM + " cm");
        }
        if (height < MIN_DIMENSION_CM || height > MAX_DIMENSION_CM) {
            throw new InvalidMeasurementsException("Height (" + height + " cm) must be between " + MIN_DIMENSION_CM + " and " + MAX_DIMENSION_CM + " cm");
        }

        // Combined length + girth: L + 2*(W + H) where L is the longest side
        int longest = Math.max(length, Math.max(width, height));
        int girth;
        if (longest == length) {
            girth = 2 * (width + height);
        } else if (longest == width) {
            girth = 2 * (length + height);
        } else {
            girth = 2 * (length + width);
        }

        int combined = longest + girth;
        if (combined > MAX_LENGTH_PLUS_GIRTH_CM) {
            throw new InvalidMeasurementsException(
                    "Combined length and girth (" + combined + " cm) exceeds maximum limit of " + MAX_LENGTH_PLUS_GIRTH_CM + " cm");
        }
    }

    private static BigDecimal validateWeight(BigDecimal weight) {
        Objects.requireNonNull(weight, "Weight cannot be null");
        BigDecimal normalized = weight.setScale(2, RoundingMode.HALF_UP);
        if (normalized.compareTo(MIN_WEIGHT_KG) < 0) {
            throw new InvalidMeasurementsException(
                    "Weight (" + normalized + " kg) is below minimum allowed weight of " + MIN_WEIGHT_KG + " kg");
        }
        if (normalized.compareTo(MAX_WEIGHT_KG) > 0) {
            throw new InvalidMeasurementsException(
                    "Weight (" + normalized + " kg) exceeds maximum allowed weight of " + MAX_WEIGHT_KG + " kg");
        }
        return normalized;
    }

    private static BigDecimal calculateVolumetricWeight(int length, int width, int height) {
        BigDecimal volume = BigDecimal.valueOf((long) length * width * height);
        return volume.divide(VOLUMETRIC_DIVISOR, 2, RoundingMode.HALF_UP);
    }

    public int getLengthCm() {
        return lengthCm;
    }

    public int getWidthCm() {
        return widthCm;
    }

    public int getHeightCm() {
        return heightCm;
    }

    public BigDecimal getPhysicalWeightKg() {
        return physicalWeightKg;
    }

    public BigDecimal getVolumetricWeightKg() {
        return volumetricWeightKg;
    }

    public BigDecimal getChargeableWeightKg() {
        return chargeableWeightKg;
    }

    public SizeCategory getSizeCategory() {
        return sizeCategory;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ParcelMeasurements that = (ParcelMeasurements) o;
        return lengthCm == that.lengthCm &&
                widthCm == that.widthCm &&
                heightCm == that.heightCm &&
                physicalWeightKg.compareTo(that.physicalWeightKg) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(lengthCm, widthCm, heightCm, physicalWeightKg.stripTrailingZeros());
    }

    @Override
    public String toString() {
        return "ParcelMeasurements{" +
                "length=" + lengthCm + "cm, width=" + widthCm + "cm, height=" + heightCm + "cm" +
                ", physical=" + physicalWeightKg + "kg, volumetric=" + volumetricWeightKg + "kg" +
                ", chargeable=" + chargeableWeightKg + "kg, category=" + sizeCategory + '}';
    }
}
