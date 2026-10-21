package com.swiftdrop.accountsservice.merchant;

/**
 * Thrown when a requested merchant tenant does not exist (RFC 7807 404 Not Found).
 */
public class MerchantNotFoundException extends RuntimeException {
    public MerchantNotFoundException(String message) {
        super(message);
    }
}
