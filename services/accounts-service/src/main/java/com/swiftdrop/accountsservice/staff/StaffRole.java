package com.swiftdrop.accountsservice.staff;

import java.util.Collection;

/**
 * Approved staff business roles within SwiftDrop.
 * Separate from Keycloak realm role assignments, but synchronized during authorization.
 */
public enum StaffRole {
    COURIER,
    DISPATCHER,
    ADMIN;

    public static boolean isStaffRole(String role) {
        if (role == null) {
            return false;
        }
        try {
            StaffRole.valueOf(role);
            return true;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    public static boolean hasAnyStaffRole(Collection<String> roles) {
        if (roles == null) {
            return false;
        }
        return roles.stream().anyMatch(StaffRole::isStaffRole);
    }
}
