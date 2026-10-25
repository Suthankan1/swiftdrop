package com.swiftdrop.security.jwt;

import java.util.Collections;
import java.util.Set;

/**
 * Authoritative set of approved SwiftDrop business roles in accordance with
 * Architecture Baseline v1.3 and docs/security/AUTHENTICATION.md.
 */
public final class SwiftDropRoles {

    public static final String ROLE_CUSTOMER = "CUSTOMER";
    public static final String ROLE_MERCHANT = "MERCHANT";
    public static final String ROLE_COURIER = "COURIER";
    public static final String ROLE_DISPATCHER = "DISPATCHER";
    public static final String ROLE_ADMIN = "ADMIN";

    public static final Set<String> ALL_ROLES = Set.of(
            ROLE_CUSTOMER,
            ROLE_MERCHANT,
            ROLE_COURIER,
            ROLE_DISPATCHER,
            ROLE_ADMIN
    );

    private SwiftDropRoles() {}

    public static boolean isValidRole(String role) {
        if (role == null) {
            return false;
        }
        return ALL_ROLES.contains(role.trim().toUpperCase());
    }

    public static Set<String> getAllowedRoles() {
        return Collections.unmodifiableSet(ALL_ROLES);
    }
}
