package com.swiftdrop.pricing.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.swiftdrop.pricing.domain.QuoteSnapshot;
import com.swiftdrop.pricing.domain.SizeCategory;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Contract-compliant QuoteResponse DTO matching openapi-pricing.yaml.
 */
public record QuoteResponse(
        @JsonProperty("quoteId")
        UUID quoteId,

        @JsonProperty("rateVersion")
        String rateVersion,

        @JsonProperty("pickupZone")
        String pickupZone,

        @JsonProperty("dropoffZone")
        String dropoffZone,

        @JsonProperty("weightKg")
        BigDecimal weightKg,

        @JsonProperty("volumetricWeightKg")
        BigDecimal volumetricWeightKg,

        @JsonProperty("chargeableWeightKg")
        BigDecimal chargeableWeightKg,

        @JsonProperty("sizeCategory")
        SizeCategory sizeCategory,

        @JsonProperty("baseFeeMinorUnits")
        long baseFeeMinorUnits,

        @JsonProperty("weightSurchargeMinorUnits")
        long weightSurchargeMinorUnits,

        @JsonProperty("totalAmountMinorUnits")
        long totalAmountMinorUnits,

        @JsonProperty("currency")
        String currency,

        @JsonProperty("createdAt")
        Instant createdAt,

        @JsonProperty("expiresAt")
        Instant expiresAt,

        @JsonProperty("isExpired")
        boolean isExpired
) {
    public static QuoteResponse fromDomain(QuoteSnapshot snapshot, Clock clock) {
        return new QuoteResponse(
                snapshot.getQuoteId(),
                snapshot.getRateVersion(),
                snapshot.getPickupZone(),
                snapshot.getDropoffZone(),
                snapshot.getPhysicalWeightKg(),
                snapshot.getVolumetricWeightKg(),
                snapshot.getChargeableWeightKg(),
                snapshot.getSizeCategory(),
                snapshot.getBaseFeeMinorUnits(),
                snapshot.getWeightSurchargeMinorUnits(),
                snapshot.getTotalAmountMinorUnits(),
                snapshot.getCurrency(),
                snapshot.getCreatedAt(),
                snapshot.getExpiresAt(),
                snapshot.isExpired(clock)
        );
    }
}
