package com.swiftdrop.deliveryservice.receipt.exception;

/**
 * Thrown when receipt data or rendering exceeds bounded resource limits (e.g. text length, line items, output size).
 * Maps to HTTP 422 Unprocessable Content.
 */
public class ReceiptResourceLimitException extends RuntimeException {
    public ReceiptResourceLimitException(String message) {
        super(message);
    }
}
