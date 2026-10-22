package com.swiftdrop.accountsservice.staff;

public class StaffRoleMismatchException extends RuntimeException {
    public StaffRoleMismatchException(String message) {
        super(message);
    }
}
