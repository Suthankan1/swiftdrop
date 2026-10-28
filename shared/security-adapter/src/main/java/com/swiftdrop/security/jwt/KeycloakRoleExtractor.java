package com.swiftdrop.security.jwt;

import org.springframework.security.oauth2.jwt.Jwt;

import java.util.*;

/**
 * Extracts and filters approved SwiftDrop application roles from validated Keycloak JWT claims.
 * Unrecognized, default, or internal Keycloak roles (e.g. default-roles-swiftdrop, offline_access, uma_authorization)
 * are ignored and never granted.
 */
public final class KeycloakRoleExtractor {

    private KeycloakRoleExtractor() {}

    public static Set<String> extractApprovedRoles(Jwt jwt) {
        if (jwt == null) {
            return Collections.emptySet();
        }

        Set<String> collectedRoles = new LinkedHashSet<>();

        // 1. Top-level "roles" claim (from SwiftDrop custom realm role mapper)
        Object rolesClaim = jwt.getClaim("roles");
        if (rolesClaim instanceof Collection<?> collection) {
            for (Object item : collection) {
                if (item instanceof String roleName && !roleName.isBlank()) {
                    collectedRoles.add(roleName.trim());
                }
            }
        }

        // 2. Standard Keycloak "realm_access.roles" claim
        Object realmAccessClaim = jwt.getClaim("realm_access");
        if (realmAccessClaim instanceof Map<?, ?> realmAccess) {
            Object realmRoles = realmAccess.get("roles");
            if (realmRoles instanceof Collection<?> collection) {
                for (Object item : collection) {
                    if (item instanceof String roleName && !roleName.isBlank()) {
                        collectedRoles.add(roleName.trim());
                    }
                }
            }
        }

        // Filter strictly against approved SwiftDrop roles
        Set<String> approvedRoles = new LinkedHashSet<>();
        for (String role : collectedRoles) {
            String normalized = role.trim().toUpperCase();
            if (SwiftDropRoles.ALL_ROLES.contains(normalized)) {
                approvedRoles.add(normalized);
            }
        }

        return Collections.unmodifiableSet(approvedRoles);
    }
}
