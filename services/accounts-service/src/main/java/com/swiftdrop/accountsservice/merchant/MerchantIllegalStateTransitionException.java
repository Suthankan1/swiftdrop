package com.swiftdrop.accountsservice.merchant;

/**
 * Thrown when an illegal merchant status transition is attempted
 * (e.g. attempting to approve a merchant that is not PENDING, or
 * attempting to suspend a merchant that is not APPROVED).
 */
public class MerchantIllegalStateTransitionException extends RuntimeException {

    public MerchantIllegalStateTransitionException(String message) {
        super(message);
    }
}
