package com.swiftdrop.accountsservice.staff;

import java.util.UUID;

/**
 * Controlled provisioning command representing an authorized administrative staff onboarding action.
 * Staff profiles cannot be created through public self-registration.
 */
public record ProvisionStaffCommand(
        String keycloakSub,
        String staffId,
        StaffRole role,
        String department,
        String assignedZone,
        UUID accountProfileId
) {
    public ProvisionStaffCommand {
        if (keycloakSub == null || keycloakSub.isBlank()) {
            throw new IllegalArgumentException("Keycloak subject is required for staff provisioning");
        }
        if (staffId == null || staffId.isBlank()) {
            throw new IllegalArgumentException("Staff ID is required for staff provisioning");
        }
        if (role == null) {
            throw new IllegalArgumentException("Staff role is required for staff provisioning");
        }
        if (department == null || department.isBlank()) {
            throw new IllegalArgumentException("Department is required for staff provisioning");
        }
    }
}
