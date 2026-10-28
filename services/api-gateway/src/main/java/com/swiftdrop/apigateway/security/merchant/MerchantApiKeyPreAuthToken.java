package com.swiftdrop.apigateway.security.merchant;

import org.springframework.security.authentication.AbstractAuthenticationToken;

import java.util.Collections;

/**
 * Pre-authenticated token holding an unverified raw merchant API key extracted from HTTP headers.
 */
public class MerchantApiKeyPreAuthToken extends AbstractAuthenticationToken {

    private final String rawApiKey;

    public MerchantApiKeyPreAuthToken(String rawApiKey) {
        super(Collections.emptyList());
        this.rawApiKey = rawApiKey;
        setAuthenticated(false);
    }

    @Override
    public Object getCredentials() {
        return rawApiKey;
    }

    @Override
    public Object getPrincipal() {
        return rawApiKey;
    }

    public String getRawApiKey() {
        return rawApiKey;
    }
}
