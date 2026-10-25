package com.swiftdrop.deliveryservice.receipt.exception;

/**
 * Thrown when a parcel exists but its receipt is not available (e.g. not in DELIVERED status, or payment unconfirmed).
 * Maps to HTTP 404 per openapi-delivery.yaml: "Receipt not available or parcel not completed".
 */
public class ReceiptNotAvailableException extends RuntimeException {
    public ReceiptNotAvailableException(String message) {
        super(message);
    }
}
