package com.swiftdrop.pricing.application;

import com.swiftdrop.pricing.domain.Money;
import com.swiftdrop.pricing.domain.ParcelMeasurements;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Command representing input parameters for a deterministic price calculation.
 */
public record CalculationCommand(
        String pickupZoneCode,
        String dropoffZoneCode,
        int lengthCm,
        int widthCm,
        int heightCm,
        BigDecimal physicalWeightKg,
        String rateVersionCode,
        String currency
) {
    public CalculationCommand {
        Objects.requireNonNull(pickupZoneCode, "pickupZoneCode cannot be null");
        Objects.requireNonNull(dropoffZoneCode, "dropoffZoneCode cannot be null");
        Objects.requireNonNull(physicalWeightKg, "physicalWeightKg cannot be null");
        currency = (currency != null && !currency.isBlank()) ? currency.trim().toUpperCase() : Money.DEFAULT_CURRENCY;
    }

    public static CalculationCommand of(String pickupZoneCode,
                                        String dropoffZoneCode,
                                        int lengthCm,
                                        int widthCm,
                                        int heightCm,
                                        BigDecimal physicalWeightKg) {
        return new CalculationCommand(
                pickupZoneCode,
                dropoffZoneCode,
                lengthCm,
                widthCm,
                heightCm,
                physicalWeightKg,
                null,
                Money.DEFAULT_CURRENCY
        );
    }

    public static CalculationCommand of(String pickupZoneCode,
                                        String dropoffZoneCode,
                                        int lengthCm,
                                        int widthCm,
                                        int heightCm,
                                        BigDecimal physicalWeightKg,
                                        String rateVersionCode) {
        return new CalculationCommand(
                pickupZoneCode,
                dropoffZoneCode,
                lengthCm,
                widthCm,
                heightCm,
                physicalWeightKg,
                rateVersionCode,
                Money.DEFAULT_CURRENCY
        );
    }

    public ParcelMeasurements toMeasurements() {
        return ParcelMeasurements.of(lengthCm, widthCm, heightCm, physicalWeightKg);
    }
}
