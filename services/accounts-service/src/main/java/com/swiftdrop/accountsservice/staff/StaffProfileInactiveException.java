package com.swiftdrop.accountsservice.staff;

public class StaffProfileInactiveException extends RuntimeException {
    private final StaffStatus status;

    public StaffProfileInactiveException(StaffStatus status, String message) {
        super(message);
        this.status = status;
    }

    public StaffStatus getStatus() {
        return status;
    }
}
