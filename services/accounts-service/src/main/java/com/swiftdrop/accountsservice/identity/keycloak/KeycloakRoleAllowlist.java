package com.swiftdrop.accountsservice.identity.keycloak;

import java.util.Collections;
import java.util.Set;

/**
 * Authoritative allowlist of approved Keycloak roles for SwiftDrop reconciliation.
 *
 * Enforces strict privilege boundaries:
 * - Public self-registration assigns only CUSTOMER.
 * - Privileged roles (COURIER, DISPATCHER, ADMIN, MERCHANT) can only be granted/revoked
 *   through authorized administrative reconciliation workflows.
 * - Arbitrary or unapproved role names are strictly rejected.
 */
public final class KeycloakRoleAllowlist {

    public static final String ROLE_CUSTOMER = "CUSTOMER";
    public static final String ROLE_MERCHANT = "MERCHANT";
    public static final String ROLE_COURIER = "COURIER";
    public static final String ROLE_DISPATCHER = "DISPATCHER";
    public static final String ROLE_ADMIN = "ADMIN";

    public static final Set<String> RECONCILIABLE_ROLES = Set.of(
            ROLE_MERCHANT,
            ROLE_COURIER,
            ROLE_DISPATCHER,
            ROLE_ADMIN
    );

    public static final Set<String> STAFF_ROLES = Set.of(
            ROLE_COURIER,
            ROLE_DISPATCHER,
            ROLE_ADMIN
    );

    public static final Set<String> ALL_SWIFTDROP_ROLES = Set.of(
            ROLE_CUSTOMER,
            ROLE_MERCHANT,
            ROLE_COURIER,
            ROLE_DISPATCHER,
            ROLE_ADMIN
    );

    private KeycloakRoleAllowlist() {}

    public static boolean isReconcilableRole(String role) {
        if (role == null) {
            return false;
        }
        return RECONCILIABLE_ROLES.contains(role.trim().toUpperCase());
    }

    public static boolean isStaffRole(String role) {
        if (role == null) {
            return false;
        }
        return STAFF_ROLES.contains(role.trim().toUpperCase());
    }

    public static boolean isAllowedRole(String role) {
        if (role == null) {
            return false;
        }
        return ALL_SWIFTDROP_ROLES.contains(role.trim().toUpperCase());
    }

    public static void validateReconcilableRole(String role) {
        if (!isReconcilableRole(role)) {
            throw new UnapprovedRoleException(
                    "Role '" + role + "' is not an approved reconcilable role. Allowed reconcilable roles: " + RECONCILIABLE_ROLES
            );
        }
    }
}
