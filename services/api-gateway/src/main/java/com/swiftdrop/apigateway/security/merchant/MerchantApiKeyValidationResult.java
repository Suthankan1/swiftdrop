package com.swiftdrop.apigateway.security.merchant;

import java.util.Collections;
import java.util.Set;

/**
 * Result of validating a merchant machine API key credential.
 */
public record MerchantApiKeyValidationResult(
        boolean authenticated,
        boolean authorized,
        String merchantId,
        String keyId,
        String keyPrefix,
        Set<String> scopes,
        String merchantStatus,
        String failureReason
) {
    public static MerchantApiKeyValidationResult success(String merchantId, String keyId, String keyPrefix, Set<String> scopes) {
        return new MerchantApiKeyValidationResult(true, true, merchantId, keyId, keyPrefix, scopes, "APPROVED", null);
    }

    public static MerchantApiKeyValidationResult unauthenticated(String reason) {
        return new MerchantApiKeyValidationResult(false, false, null, null, null, Collections.emptySet(), null, reason);
    }

    public static MerchantApiKeyValidationResult forbidden(String merchantId, String merchantStatus, String reason) {
        return new MerchantApiKeyValidationResult(true, false, merchantId, null, null, Collections.emptySet(), merchantStatus, reason);
    }
}
