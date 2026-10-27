package com.swiftdrop.accountsservice.merchant;

/**
 * Thrown when an expected version / review token conflict occurs during
 * an administrative decision command.
 */
public class MerchantConcurrencyConflictException extends RuntimeException {

    public MerchantConcurrencyConflictException(String message) {
        super(message);
    }
}
