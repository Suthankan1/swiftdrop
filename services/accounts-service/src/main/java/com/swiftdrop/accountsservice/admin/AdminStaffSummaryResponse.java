package com.swiftdrop.accountsservice.admin;

import com.swiftdrop.accountsservice.staff.StaffRole;
import com.swiftdrop.accountsservice.staff.StaffStatus;

import java.time.Instant;
import java.util.UUID;

public record AdminStaffSummaryResponse(
        UUID profileId,
        UUID accountProfileId,
        String staffId,
        String keycloakSub,
        StaffRole role,
        String department,
        String assignedZone,
        StaffStatus businessStatus,
        Instant createdAt,
        Instant updatedAt
) { }
