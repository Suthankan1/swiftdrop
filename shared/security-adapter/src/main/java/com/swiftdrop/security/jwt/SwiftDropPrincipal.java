package com.swiftdrop.security.jwt;

import java.io.Serializable;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Validated security principal representing an authenticated SwiftDrop user.
 */
public record SwiftDropPrincipal(
        String subject,
        Set<String> roles,
        String username,
        String email,
        Map<String, Object> claims
) implements Serializable {

    public SwiftDropPrincipal {
        Objects.requireNonNull(subject, "subject must not be null");
        roles = roles != null ? Collections.unmodifiableSet(roles) : Collections.emptySet();
        claims = claims != null ? Collections.unmodifiableMap(claims) : Collections.emptyMap();
    }

    public boolean hasRole(String role) {
        if (role == null) {
            return false;
        }
        return roles.contains(role.trim().toUpperCase());
    }

    public boolean isAdmin() {
        return hasRole(SwiftDropRoles.ROLE_ADMIN);
    }

    public boolean isMerchant() {
        return hasRole(SwiftDropRoles.ROLE_MERCHANT);
    }

    public boolean isCourier() {
        return hasRole(SwiftDropRoles.ROLE_COURIER);
    }

    public boolean isDispatcher() {
        return hasRole(SwiftDropRoles.ROLE_DISPATCHER);
    }

    public boolean isCustomer() {
        return hasRole(SwiftDropRoles.ROLE_CUSTOMER);
    }
}
