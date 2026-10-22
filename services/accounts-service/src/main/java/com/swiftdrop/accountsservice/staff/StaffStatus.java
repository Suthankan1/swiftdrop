package com.swiftdrop.accountsservice.staff;

/**
 * Business lifecycle status of a staff member.
 * Only ACTIVE staff members may perform operational actions.
 */
public enum StaffStatus {
    ACTIVE,
    SUSPENDED,
    INACTIVE;

    public boolean isActive() {
        return this == ACTIVE;
    }
}
