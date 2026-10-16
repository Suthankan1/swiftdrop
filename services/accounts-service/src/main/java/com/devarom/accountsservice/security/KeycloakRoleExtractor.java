package com.devarom.accountsservice.security;

import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Extracts and filters approved Devarom application roles from validated Keycloak JWT claims.
 * Unrecognized, default, or internal Keycloak roles (e.g., default-roles-devarom, offline_access, uma_authorization)
 * are ignored and never granted.
 */
public final class KeycloakRoleExtractor {

    public static final Set<String> DEVAROM_ROLES = Set.of(
            "CUSTOMER",
            "MERCHANT",
            "COURIER",
            "DISPATCHER",
            "ADMIN"
    );

    private KeycloakRoleExtractor() {
    }

    public static List<String> extractApprovedRoles(Jwt jwt) {
        if (jwt == null) {
            return Collections.emptyList();
        }

        Set<String> collectedRoles = new LinkedHashSet<>();

        // 1. Check top-level "roles" claim (from Devarom custom realm role mapper)
        Object rolesClaim = jwt.getClaim("roles");
        if (rolesClaim instanceof Collection<?> collection) {
            for (Object item : collection) {
                if (item instanceof String roleName && !roleName.isBlank()) {
                    collectedRoles.add(roleName);
                }
            }
        }

        // 2. Check standard Keycloak "realm_access.roles" claim
        Object realmAccessClaim = jwt.getClaim("realm_access");
        if (realmAccessClaim instanceof Map<?, ?> realmAccess) {
            Object realmRoles = realmAccess.get("roles");
            if (realmRoles instanceof Collection<?> collection) {
                for (Object item : collection) {
                    if (item instanceof String roleName && !roleName.isBlank()) {
                        collectedRoles.add(roleName);
                    }
                }
            }
        }

        return collectedRoles.stream()
                .filter(DEVAROM_ROLES::contains)
                .sorted()
                .toList();
    }
}
