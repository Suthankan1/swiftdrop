package com.swiftdrop.deliveryservice.idempotency;

/**
 * Status of an idempotent request transaction.
 */
public enum IdempotencyStatus {
    IN_PROGRESS,
    COMPLETED,
    FAILED
}
