package com.swiftdrop.apigateway.security.merchant;

import reactor.core.publisher.Mono;

/**
 * Interface for verifying merchant machine credentials at the API Gateway edge.
 * Authoritative verification in production is executed against Accounts & Partners.
 */
public interface MerchantApiKeyValidator {

    /**
     * Validates raw API key and required scope.
     *
     * @param rawApiKey the raw secret key received in X-API-Key header
     * @param requiredScope the minimum scope required by the target endpoint (e.g. "parcels:create")
     * @return validation result
     */
    Mono<MerchantApiKeyValidationResult> validateKey(String rawApiKey, String requiredScope);
}
