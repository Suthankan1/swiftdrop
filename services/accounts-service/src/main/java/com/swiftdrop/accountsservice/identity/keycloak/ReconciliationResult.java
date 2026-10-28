package com.swiftdrop.accountsservice.identity.keycloak;

import java.time.Instant;
import java.util.Objects;

/**
 * Structured outcome of an identity role reconciliation operation.
 *
 * @param subject   the Keycloak subject UUID.
 * @param roleName  the targeted approved role name.
 * @param outcome   structured outcome (SUCCESS, NO_OP, FAILED, PENDING_RETRY, DENIED).
 * @param message   descriptive outcome summary (sanitized, zero secrets).
 * @param timestamp UTC timestamp of the reconciliation attempt.
 */
public record ReconciliationResult(
        String subject,
        String roleName,
        ReconciliationOutcome outcome,
        String message,
        Instant timestamp
) {
    public ReconciliationResult {
        Objects.requireNonNull(subject, "subject is required");
        Objects.requireNonNull(roleName, "roleName is required");
        Objects.requireNonNull(outcome, "outcome is required");
        Objects.requireNonNull(timestamp, "timestamp is required");
    }

    public static ReconciliationResult success(String subject, String roleName, String message) {
        return new ReconciliationResult(subject, roleName, ReconciliationOutcome.SUCCESS, message, Instant.now());
    }

    public static ReconciliationResult noOp(String subject, String roleName, String message) {
        return new ReconciliationResult(subject, roleName, ReconciliationOutcome.NO_OP, message, Instant.now());
    }

    public static ReconciliationResult failed(String subject, String roleName, String message) {
        return new ReconciliationResult(subject, roleName, ReconciliationOutcome.FAILED, message, Instant.now());
    }

    public static ReconciliationResult denied(String subject, String roleName, String message) {
        return new ReconciliationResult(subject, roleName, ReconciliationOutcome.DENIED, message, Instant.now());
    }

    public static ReconciliationResult pendingRetry(String subject, String roleName, String message) {
        return new ReconciliationResult(subject, roleName, ReconciliationOutcome.PENDING_RETRY, message, Instant.now());
    }
}
