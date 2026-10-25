package com.swiftdrop.accountsservice.merchant;

/**
 * Domain exception thrown when a merchant enrollment command conflicts with
 * an existing merchant tenant owner or business registration number.
 */
public class MerchantConflictException extends RuntimeException {

    public MerchantConflictException(String message) {
        super(message);
    }

    public MerchantConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
