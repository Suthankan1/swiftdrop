package com.swiftdrop.deliveryservice.proof.retention;

import java.time.Instant;
import java.util.Objects;

/**
 * Result of evaluating a proof record for retention cleanup eligibility.
 *
 * @param eligible              true if eligible for immediate privacy-safe cleanup, false otherwise
 * @param rejectionReason       explicit failure reason if ineligible, null if eligible
 * @param rejectionDetails      explanatory details for audit and debugging (strictly non-sensitive)
 * @param eligibleExpiryInstant calculated expiry boundary timestamp if eligible
 */
public record RetentionEvaluation(
        boolean eligible,
        RetentionRejectionReason rejectionReason,
        String rejectionDetails,
        Instant eligibleExpiryInstant
) {
    public static RetentionEvaluation eligible(Instant expiry) {
        Objects.requireNonNull(expiry, "expiry instant cannot be null for eligible evaluation");
        return new RetentionEvaluation(true, null, "Proof satisfies all approved retention criteria", expiry);
    }

    public static RetentionEvaluation ineligible(RetentionRejectionReason reason, String details) {
        Objects.requireNonNull(reason, "rejectionReason cannot be null for ineligible evaluation");
        return new RetentionEvaluation(false, reason, details != null ? details : reason.name(), null);
    }
}
