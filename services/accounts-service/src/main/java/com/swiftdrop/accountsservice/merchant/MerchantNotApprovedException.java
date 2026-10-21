package com.swiftdrop.accountsservice.merchant;

/**
 * Thrown when an authenticated merchant attempts an operation that requires APPROVED status
 * while in PENDING, SUSPENDED, or REJECTED state (RFC 7807 403 Forbidden).
 */
public class MerchantNotApprovedException extends RuntimeException {
    private final MerchantStatus currentStatus;

    public MerchantNotApprovedException(MerchantStatus currentStatus, String message) {
        super(message);
        this.currentStatus = currentStatus;
    }

    public MerchantStatus getCurrentStatus() {
        return currentStatus;
    }
}
