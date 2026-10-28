package com.swiftdrop.deliveryservice.security.merchant;

import java.util.Set;
import java.util.UUID;

/**
 * Result of merchant API key validation against authoritative Accounts & Partners context.
 */
public record MerchantKeyValidationResult(
        boolean isValid,
        UUID merchantId,
        String businessName,
        String status,
        Set<String> scopes,
        String rejectionReason
) {
    public static MerchantKeyValidationResult invalid(String reason) {
        return new MerchantKeyValidationResult(false, null, null, null, Set.of(), reason);
    }

    public static MerchantKeyValidationResult valid(UUID merchantId, String businessName, String status, Set<String> scopes) {
        return new MerchantKeyValidationResult(true, merchantId, businessName, status, scopes, null);
    }

    public boolean isApproved() {
        return isValid && "APPROVED".equalsIgnoreCase(status);
    }

    public boolean hasScope(String scope) {
        return scopes != null && scopes.contains(scope);
    }
}
