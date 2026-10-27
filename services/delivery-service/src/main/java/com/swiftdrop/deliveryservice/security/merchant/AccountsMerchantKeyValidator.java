package com.swiftdrop.deliveryservice.security.merchant;

/**
 * Port for validating merchant API key credentials against Accounts & Partners.
 */
public interface AccountsMerchantKeyValidator {
    MerchantKeyValidationResult validateApiKey(String rawApiKey);
}
