package com.swiftdrop.pricing.domain;

import com.swiftdrop.pricing.error.AmbiguousRateRuleException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * Validates a proposed draft rule against existing schema and calculation invariants.
 * This pure domain boundary performs no persistence, publication or authorization.
 * A future command must lock the parent and supply its complete current rule set.
 */
public final class DraftRateRuleValidator {

    private static final BigDecimal MIN_WEIGHT = new BigDecimal("0.01");
    private static final BigDecimal MAX_WEIGHT = new BigDecimal("30.00");

    public void validate(RateVersion version, RateRule candidate, List<RateRule> existingRules) {
        Objects.requireNonNull(version, "version cannot be null").ensureMutable();
        Objects.requireNonNull(candidate, "candidate cannot be null");
        Objects.requireNonNull(existingRules, "existingRules cannot be null");

        if (!Objects.equals(version.getId(), candidate.getRateVersionId())) {
            throw new IllegalArgumentException("Rule must belong to the requested rate version");
        }
        if (!version.getCurrency().equals(candidate.getBaseFee().getCurrency())) {
            throw new IllegalArgumentException("Rule currency must match the rate version");
        }
        validateWeight(candidate.getMinWeightKg(), MIN_WEIGHT, "minWeightKg");
        validateWeight(candidate.getMaxWeightKg(), MIN_WEIGHT, "maxWeightKg");
        if (candidate.getMaxWeightKg().compareTo(candidate.getSizeCategory().getMaxWeightKg()) > 0) {
            throw new IllegalArgumentException("Weight band exceeds size category maximum");
        }
        // The existing schema has no upper bound on threshold. Do not invent one.
        if (candidate.getWeightThresholdKg().signum() < 0
                || candidate.getWeightThresholdKg().stripTrailingZeros().scale() > 2
                || candidate.getWeightThresholdKg().compareTo(new BigDecimal("9999.99")) > 0) {
            throw new IllegalArgumentException("weightThresholdKg must fit nonnegative NUMERIC(6,2)");
        }

        // Exact Money arithmetic must remain representable over the entire band.
        candidate.calculateTotalFee(candidate.getMaxWeightKg());

        for (RateRule existing : existingRules) {
            if (!Objects.equals(version.getId(), existing.getRateVersionId())) {
                throw new IllegalArgumentException("Existing rules must belong to the requested rate version");
            }
            if (candidate.getId() != null && candidate.getId().equals(existing.getId())) {
                continue; // Replacing this draft rule, not overlapping a sibling.
            }
            if (candidate.getPickupZoneCode().equals(existing.getPickupZoneCode())
                    && candidate.getDropoffZoneCode().equals(existing.getDropoffZoneCode())
                    && candidate.getSizeCategory() == existing.getSizeCategory()
                    && candidate.getMinWeightKg().compareTo(existing.getMaxWeightKg()) <= 0
                    && candidate.getMaxWeightKg().compareTo(existing.getMinWeightKg()) >= 0) {
                throw new AmbiguousRateRuleException("Draft rule overlaps an existing route/category weight band");
            }
        }
    }

    private void validateWeight(BigDecimal weight, BigDecimal minimum, String field) {
        if (weight.compareTo(minimum) < 0 || weight.compareTo(MAX_WEIGHT) > 0
                || weight.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException(field + " must be 0.01..30.00kg with at most two decimal places");
        }
    }
}
