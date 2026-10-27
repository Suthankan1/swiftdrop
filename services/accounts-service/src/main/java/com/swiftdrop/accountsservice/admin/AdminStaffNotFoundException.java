package com.swiftdrop.accountsservice.admin;

import java.util.UUID;

public class AdminStaffNotFoundException extends RuntimeException {
    public AdminStaffNotFoundException(UUID profileId) {
        super("Staff profile not found: " + profileId);
    }
}
