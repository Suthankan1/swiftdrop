package com.swiftdrop.accountsservice.merchant;

import java.util.UUID;

/**
 * Result of a tenant authorization check.
 */
public record TenantAuthorizationDecision(
        boolean authorized,
        UUID merchantId,
        MerchantStatus status,
        String denialReason
) {
    public static TenantAuthorizationDecision allowed(UUID merchantId, MerchantStatus status) {
        return new TenantAuthorizationDecision(true, merchantId, status, null);
    }

    public static TenantAuthorizationDecision denied(UUID merchantId, MerchantStatus status, String reason) {
        return new TenantAuthorizationDecision(false, merchantId, status, reason);
    }

    public static TenantAuthorizationDecision denied(String reason) {
        return new TenantAuthorizationDecision(false, null, null, reason);
    }
}
