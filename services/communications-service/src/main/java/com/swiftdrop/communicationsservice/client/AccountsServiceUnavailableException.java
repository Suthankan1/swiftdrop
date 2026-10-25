package com.swiftdrop.communicationsservice.client;

/**
 * Thrown when Accounts Service is unavailable or deadline exceeded (transient failure eligible for retry).
 */
public class AccountsServiceUnavailableException extends AccountsContactException {
    public AccountsServiceUnavailableException(String message) {
        super(message);
    }

    public AccountsServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
