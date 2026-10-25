package com.swiftdrop.deliveryservice.receipt.exception;

/**
 * Thrown when an internal PDFBox rendering failure occurs.
 */
public class ReceiptRenderingException extends RuntimeException {
    public ReceiptRenderingException(String message, Throwable cause) {
        super(message, cause);
    }
}
