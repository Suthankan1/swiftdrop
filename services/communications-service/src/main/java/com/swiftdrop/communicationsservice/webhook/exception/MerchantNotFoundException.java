package com.swiftdrop.communicationsservice.webhook.exception;

/**
 * Thrown when an authenticated subject is not associated with an Accounts merchant tenant.
 */
public class MerchantNotFoundException extends RuntimeException {
    public MerchantNotFoundException(String message) {
        super(message);
    }
}
