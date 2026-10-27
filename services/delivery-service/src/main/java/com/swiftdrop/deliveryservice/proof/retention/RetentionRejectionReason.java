package com.swiftdrop.deliveryservice.proof.retention;

/**
 * Enumerates all specific, testable reasons a proof record may be deemed ineligible for retention cleanup.
 */
public enum RetentionRejectionReason {
    /**
     * Master switch or formal policy approval (DEC-RET-01) is missing.
     */
    POLICY_NOT_APPROVED,

    /**
     * Required policy configuration (retention days, terminal states, clock event) is missing or invalid.
     */
    POLICY_CONFIG_MISSING,

    /**
     * The proof entity record was null or could not be loaded.
     */
    MISSING_PROOF_RECORD,

    /**
     * Authoritative parcel context was null or could not be resolved.
     */
    MISSING_PARCEL_CONTEXT,

    /**
     * The parcelId associated with the proof does not match the authoritative parcel entity.
     */
    PARCEL_ID_MISMATCH,

    /**
     * Proof record has already been purged and tombstoned.
     */
    ALREADY_PURGED,

    /**
     * Proof has no stored object reference.
     */
    MISSING_OBJECT_REFERENCE,

    /**
     * The storage object reference format violates opaque security constraints.
     */
    MALFORMED_OBJECT_REFERENCE,

    /**
     * Parcel is not in an approved terminal state (e.g. still in-transit or stage not listed in policy).
     */
    INELIGIBLE_PARCEL_STATE,

    /**
     * The proof or parcel is flagged under an active legal or operational hold.
     */
    ACTIVE_LEGAL_OR_OPERATIONAL_HOLD,

    /**
     * Hold status could not be authoritatively confirmed; failing closed per security requirements.
     */
    HOLD_STATUS_INDETERMINATE,

    /**
     * Retention clock starting timestamp is null, invalid, or in the future.
     */
    INVALID_CLOCK_TIMESTAMP,

    /**
     * Configured retention period has not elapsed relative to current evaluation clock.
     */
    RETENTION_PERIOD_NOT_ELAPSED
}
