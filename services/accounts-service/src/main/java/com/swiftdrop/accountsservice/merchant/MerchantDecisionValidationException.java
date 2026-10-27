package com.swiftdrop.accountsservice.merchant;

/**
 * Thrown when an administrative command request body or input fails validation
 * (e.g. suspension reason is missing, empty, or exceeds maximum length).
 */
public class MerchantDecisionValidationException extends RuntimeException {

    public MerchantDecisionValidationException(String message) {
        super(message);
    }
}
