package com.swiftdrop.accountsservice.staff;

public class StaffAccessDeniedException extends RuntimeException {
    public StaffAccessDeniedException(String message) {
        super(message);
    }
}
