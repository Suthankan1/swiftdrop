package com.swiftdrop.deliveryservice.receipt.exception;

/**
 * Thrown when an authenticated requester does not have authorization to view the receipt of a given parcel.
 * Maps to HTTP 403 Forbidden.
 */
public class ReceiptAccessDeniedException extends RuntimeException {
    public ReceiptAccessDeniedException(String message) {
        super(message);
    }
}
