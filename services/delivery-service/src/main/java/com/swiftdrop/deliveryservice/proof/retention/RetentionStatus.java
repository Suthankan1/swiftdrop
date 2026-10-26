package com.swiftdrop.deliveryservice.proof.retention;

/**
 * Authoritative lifecycle retention status for a Proof of Delivery record.
 */
public enum RetentionStatus {
    /**
     * Active proof record whose evidence is retained and queryable by authorized actors.
     */
    ACTIVE,

    /**
     * Purged proof record whose binary evidence has been permanently deleted from private storage
     * and whose personal recipient details have been securely tombstoned.
     */
    PURGED
}
