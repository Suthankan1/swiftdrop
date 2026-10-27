package com.swiftdrop.deliveryservice.security.merchant;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Authentication token representing a verified merchant machine or principal identity.
 * Carries the verified merchant tenant UUID, granted scopes, and the ROLE_MERCHANT authority.
 */
public class MerchantAuthenticationToken extends AbstractAuthenticationToken {

    private final UUID merchantId;
    private final String businessName;
    private final String credentialType; // "API_KEY" or "BEARER_JWT"
    private final Set<String> scopes;
    private final String principalName;

    public MerchantAuthenticationToken(UUID merchantId, String businessName, String credentialType, Set<String> scopes) {
        super(buildAuthorities(scopes));
        this.merchantId = merchantId;
        this.businessName = businessName;
        this.credentialType = credentialType;
        this.scopes = scopes != null ? Set.copyOf(scopes) : Set.of();
        this.principalName = "merchant:" + (merchantId != null ? merchantId : "unknown");
        setAuthenticated(true);
    }

    private static java.util.Collection<org.springframework.security.core.GrantedAuthority> buildAuthorities(Set<String> scopes) {
        java.util.List<org.springframework.security.core.GrantedAuthority> authorities = new java.util.ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_MERCHANT"));
        if (scopes != null) {
            for (String scope : scopes) {
                authorities.add(new SimpleGrantedAuthority("SCOPE_" + scope));
            }
        }
        return authorities;
    }

    public UUID getMerchantId() {
        return merchantId;
    }

    public String getBusinessName() {
        return businessName;
    }

    public String getCredentialType() {
        return credentialType;
    }

    public Set<String> getScopes() {
        return scopes;
    }

    public boolean hasScope(String scope) {
        return scopes.contains(scope);
    }

    @Override
    public Object getCredentials() {
        return "[PROTECTED]";
    }

    @Override
    public Object getPrincipal() {
        return principalName;
    }

    @Override
    public String getName() {
        return principalName;
    }
}
