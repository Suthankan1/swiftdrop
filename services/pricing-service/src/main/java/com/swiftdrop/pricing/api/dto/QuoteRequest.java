package com.swiftdrop.pricing.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * Request payload for calculating a delivery quote per openapi-pricing.yaml.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record QuoteRequest(
        @NotBlank(message = "pickupZone is required")
        @JsonProperty("pickupZone")
        String pickupZone,

        @NotBlank(message = "dropoffZone is required")
        @JsonProperty("dropoffZone")
        String dropoffZone,

        @NotNull(message = "weightKg is required")
        @DecimalMin(value = "0.01", message = "weightKg must be at least 0.01 kg")
        @DecimalMax(value = "30.00", message = "weightKg must not exceed 30.00 kg")
        @Digits(integer = 2, fraction = 2, message = "weightKg must have at most 2 decimal places")
        @JsonProperty("weightKg")
        BigDecimal weightKg,

        @NotNull(message = "lengthCm is required")
        @Min(value = 1, message = "lengthCm must be at least 1 cm")
        @Max(value = 150, message = "lengthCm must not exceed 150 cm")
        @JsonProperty("lengthCm")
        Integer lengthCm,

        @NotNull(message = "widthCm is required")
        @Min(value = 1, message = "widthCm must be at least 1 cm")
        @Max(value = 150, message = "widthCm must not exceed 150 cm")
        @JsonProperty("widthCm")
        Integer widthCm,

        @NotNull(message = "heightCm is required")
        @Min(value = 1, message = "heightCm must be at least 1 cm")
        @Max(value = 150, message = "heightCm must not exceed 150 cm")
        @JsonProperty("heightCm")
        Integer heightCm
) {
}
