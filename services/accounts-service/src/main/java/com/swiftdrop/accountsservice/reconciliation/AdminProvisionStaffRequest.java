package com.swiftdrop.accountsservice.reconciliation;

import com.swiftdrop.accountsservice.staff.ProvisionStaffCommand;
import com.swiftdrop.accountsservice.staff.StaffRole;

import java.util.UUID;

/**
 * Request payload for administrative staff provisioning and role reconciliation.
 */
public record AdminProvisionStaffRequest(
        String keycloakSub,
        String staffId,
        StaffRole role,
        String department,
        String assignedZone,
        UUID accountProfileId
) {
    public ProvisionStaffCommand toCommand() {
        return new ProvisionStaffCommand(keycloakSub, staffId, role, department, assignedZone, accountProfileId);
    }
}
