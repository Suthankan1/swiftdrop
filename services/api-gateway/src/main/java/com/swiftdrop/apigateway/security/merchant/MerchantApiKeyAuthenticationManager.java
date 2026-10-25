package com.swiftdrop.apigateway.security.merchant;

import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * ReactiveAuthenticationManager validating merchant machine API-key credentials against
 * the configured MerchantApiKeyValidator.
 */
@Component
public class MerchantApiKeyAuthenticationManager implements ReactiveAuthenticationManager {

    private final MerchantApiKeyValidator validator;

    public MerchantApiKeyAuthenticationManager(MerchantApiKeyValidator validator) {
        this.validator = validator;
    }

    @Override
    public Mono<Authentication> authenticate(Authentication authentication) {
        if (!(authentication instanceof MerchantApiKeyPreAuthToken preAuthToken)) {
            return Mono.empty();
        }

        String rawKey = preAuthToken.getRawApiKey();
        // Merchant parcel creation requires parcels:create scope
        return validator.validateKey(rawKey, "parcels:create")
                .flatMap(result -> {
                    if (!result.authenticated()) {
                        return Mono.error(new MerchantAuthenticationException(
                                result.failureReason() != null ? result.failureReason() : "Invalid merchant API key",
                                false,
                                null,
                                "INVALID_KEY"
                        ));
                    }

                    if (!result.authorized()) {
                        return Mono.error(new MerchantAuthenticationException(
                                result.failureReason() != null ? result.failureReason() : "Merchant organization not approved",
                                true,
                                result.merchantId(),
                                "MERCHANT_UNAPPROVED"
                        ));
                    }

                    MerchantApiKeyAuthenticationToken authenticatedToken = new MerchantApiKeyAuthenticationToken(
                            result.merchantId(),
                            result.keyId(),
                            result.keyPrefix(),
                            result.scopes()
                    );
                    return Mono.just(authenticatedToken);
                });
    }
}
