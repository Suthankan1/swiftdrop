package com.swiftdrop.apigateway.security.merchant;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Authenticated token representing a verified merchant machine client.
 * Confined strictly to ROLE_MERCHANT authority and bound to the verified merchantId.
 */
public class MerchantApiKeyAuthenticationToken extends AbstractAuthenticationToken {

    public record MerchantPrincipal(
            String merchantId,
            String keyId,
            String keyPrefix,
            Set<String> scopes
    ) {}

    private final MerchantPrincipal principal;

    public MerchantApiKeyAuthenticationToken(
            String merchantId,
            String keyId,
            String keyPrefix,
            Set<String> scopes
    ) {
        super(List.of(new SimpleGrantedAuthority("ROLE_MERCHANT")));
        this.principal = new MerchantPrincipal(merchantId, keyId, keyPrefix, scopes != null ? scopes : Collections.emptySet());
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return "[PROTECTED]";
    }

    @Override
    public Object getPrincipal() {
        return principal;
    }

    public MerchantPrincipal getMerchantPrincipal() {
        return principal;
    }
}
