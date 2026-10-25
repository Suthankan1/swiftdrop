package com.swiftdrop.accountsservice.identity.keycloak;

/**
 * Structured outcome states for identity reconciliation.
 */
public enum ReconciliationOutcome {
    /**
     * Role was mutated successfully and post-operation state was cryptographically verified.
     */
    SUCCESS,

    /**
     * Role was already in the desired state; no mutation was necessary (idempotent no-op).
     */
    NO_OP,

    /**
     * Reconciliation operation failed (e.g. network timeout, missing identity, or communication error).
     */
    FAILED,

    /**
     * Reconciliation is pending asynchronous retry due to a recoverable transient error.
     */
    PENDING_RETRY,

    /**
     * Operation was rejected by security authorization or allowlist policy.
     */
    DENIED
}
