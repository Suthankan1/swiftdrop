package com.swiftdrop.communicationsservice.webhook.exception;

/**
 * Thrown when a merchant account is not in APPROVED lifecycle status (e.g. PENDING, SUSPENDED, REJECTED).
 */
public class MerchantNotApprovedException extends RuntimeException {
    public MerchantNotApprovedException(String message) {
        super(message);
    }
}
