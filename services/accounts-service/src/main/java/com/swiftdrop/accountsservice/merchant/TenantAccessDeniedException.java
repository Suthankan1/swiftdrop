package com.swiftdrop.accountsservice.merchant;

/**
 * Thrown when an authenticated actor attempts unauthorized cross-tenant operations
 * or lacks tenant association (RFC 7807 403 Forbidden).
 */
public class TenantAccessDeniedException extends RuntimeException {
    public TenantAccessDeniedException(String message) {
        super(message);
    }
}
