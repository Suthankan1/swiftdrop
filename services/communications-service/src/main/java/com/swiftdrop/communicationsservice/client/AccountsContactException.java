package com.swiftdrop.communicationsservice.client;

/**
 * Base exception for Accounts recipient contact resolution failures.
 */
public class AccountsContactException extends RuntimeException {
    public AccountsContactException(String message) {
        super(message);
    }

    public AccountsContactException(String message, Throwable cause) {
        super(message, cause);
    }
}
