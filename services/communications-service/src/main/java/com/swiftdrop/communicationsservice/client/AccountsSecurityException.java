package com.swiftdrop.communicationsservice.client;

/**
 * Thrown when Accounts Service rejects machine authorization or identity validation (fail closed).
 */
public class AccountsSecurityException extends AccountsContactException {
    public AccountsSecurityException(String message) {
        super(message);
    }

    public AccountsSecurityException(String message, Throwable cause) {
        super(message, cause);
    }
}
