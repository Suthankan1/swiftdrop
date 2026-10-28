package com.swiftdrop.deliveryservice.security.merchant;

/**
 * Thrown when Accounts service is unreachable or deadline exceeded during merchant credential validation.
 */
public class AccountsUnavailableException extends RuntimeException {
    public AccountsUnavailableException(String message) {
        super(message);
    }

    public AccountsUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
