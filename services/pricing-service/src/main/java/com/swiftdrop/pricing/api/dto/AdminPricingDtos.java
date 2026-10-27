package com.swiftdrop.pricing.api.dto;

import com.swiftdrop.pricing.domain.RateVersionStatus;
import com.swiftdrop.pricing.domain.SizeCategory;
import com.swiftdrop.pricing.persistence.*;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class AdminPricingDtos {
    private AdminPricingDtos() { }

    public record ZoneCreate(
            @NotBlank @Size(max = 32) @Pattern(regexp = "^[A-Z][A-Z0-9_]*$") String zoneCode,
            @NotBlank @Size(max = 128) String name,
            @NotNull @Size(max = 512) String description,
            @NotNull @Positive Integer standardTransitHours) { }

    public record ZoneUpdate(
            @NotBlank @Size(max = 128) String name,
            @NotNull @Size(max = 512) String description,
            @NotNull @Positive Integer standardTransitHours,
            @NotNull @PositiveOrZero Long expectedRevision) { }

    public record ExpectedRevision(@NotNull @PositiveOrZero Long expectedRevision) { }

    public record DraftCreate(
            @NotBlank @Size(max = 64) String versionCode,
            @NotNull @Pattern(regexp = "LKR") String currency,
            @NotNull Instant effectiveFrom,
            Instant effectiveTo,
            @Size(max = 512) String description) { }

    public record RuleWrite(
            @NotNull @PositiveOrZero Long expectedRevision,
            @NotBlank @Size(max = 32) @Pattern(regexp = "^[A-Z][A-Z0-9_]*$") String pickupZoneCode,
            @NotBlank @Size(max = 32) @Pattern(regexp = "^[A-Z][A-Z0-9_]*$") String dropoffZoneCode,
            @NotNull SizeCategory sizeCategory,
            @NotNull @DecimalMin("0.01") @DecimalMax("30.00") @Digits(integer = 2, fraction = 2) BigDecimal minWeightKg,
            @NotNull @DecimalMin("0.01") @DecimalMax("30.00") @Digits(integer = 2, fraction = 2) BigDecimal maxWeightKg,
            @NotNull @PositiveOrZero Long baseFeeMinorUnits,
            @NotNull @PositiveOrZero Long perKgSurchargeMinorUnits,
            @NotNull @DecimalMin("0.00") @DecimalMax("30.00") @Digits(integer = 2, fraction = 2) BigDecimal weightThresholdKg) { }

    public record Activate(@NotNull @PositiveOrZero Long expectedRevision,
                           @com.fasterxml.jackson.annotation.JsonProperty(required = true) UUID expectedActiveVersionId) { }

    public record PageView<T>(List<T> items, int page, int size, long totalElements) { }

    public record ZoneView(UUID id, String zoneCode, String name, String description,
                           int standardTransitHours, boolean active, long revision, Instant createdAt, Instant updatedAt) {
        public static ZoneView from(ZoneEntity z) {
            return new ZoneView(z.getId(), z.getZoneCode(), z.getName(), z.getDescription(),
                    z.getStandardTransitHours(), z.isActive(), z.getRevision(), z.getCreatedAt(), z.getUpdatedAt());
        }
    }

    public record VersionView(UUID id, String versionCode, String currency, RateVersionStatus status,
                              Instant effectiveFrom, Instant effectiveTo, String description, long revision,
                              Instant createdAt, Instant activatedAt, Instant archivedAt) {
        public static VersionView from(RateVersionEntity v) {
            return new VersionView(v.getId(), v.getVersionCode(), v.getCurrency(), v.getStatus(),
                    v.getEffectiveFrom(), v.getEffectiveTo(), v.getDescription(), v.getRevision(),
                    v.getCreatedAt(), v.getActivatedAt(), v.getArchivedAt());
        }
    }

    public record RuleView(UUID id, UUID rateVersionId, String pickupZoneCode, String dropoffZoneCode,
                           SizeCategory sizeCategory, BigDecimal minWeightKg, BigDecimal maxWeightKg,
                           long baseFeeMinorUnits, long perKgSurchargeMinorUnits, BigDecimal weightThresholdKg,
                           long revision) {
        public static RuleView from(RateRuleEntity r, long revision) {
            return new RuleView(r.getId(), r.getRateVersionId(), r.getPickupZoneCode(), r.getDropoffZoneCode(),
                    r.getSizeCategory(), r.getMinWeightKg(), r.getMaxWeightKg(), r.getBaseFeeMinorUnits(),
                    r.getPerKgSurchargeMinorUnits(), r.getWeightThresholdKg(), revision);
        }
    }
}
