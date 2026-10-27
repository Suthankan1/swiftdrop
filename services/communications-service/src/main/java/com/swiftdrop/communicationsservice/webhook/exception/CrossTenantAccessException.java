package com.swiftdrop.communicationsservice.webhook.exception;

/**
 * Thrown when a merchant attempts to read or mutate a webhook subscription owned by another tenant.
 */
public class CrossTenantAccessException extends RuntimeException {
    public CrossTenantAccessException(String message) {
        super(message);
    }
}
