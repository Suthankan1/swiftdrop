package com.swiftdrop.accountsservice.staff;

public class StaffProfileNotFoundException extends RuntimeException {
    public StaffProfileNotFoundException(String message) {
        super(message);
    }
}
