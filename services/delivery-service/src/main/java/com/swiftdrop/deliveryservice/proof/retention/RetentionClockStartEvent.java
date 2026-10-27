package com.swiftdrop.deliveryservice.proof.retention;

/**
 * Declares the authoritative domain event that starts the retention period clock.
 *
 * <p>Subject to formal policy sign-off under DEC-RET-01.</p>
 */
public enum RetentionClockStartEvent {
    /**
     * Retention clock starts at the exact timestamp the proof was uploaded and persisted.
     */
    PROOF_UPLOAD_TIME,

    /**
     * Retention clock starts when the associated parcel entered an approved terminal status
     * (e.g. DELIVERED or FAILED).
     */
    PARCEL_TERMINAL_TIME
}
