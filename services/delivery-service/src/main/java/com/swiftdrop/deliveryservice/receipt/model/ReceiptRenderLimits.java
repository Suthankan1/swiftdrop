package com.swiftdrop.deliveryservice.receipt.model;

/**
 * Explicit resource limits enforced during PDF receipt rendering to prevent resource exhaustion (DoS).
 */
public record ReceiptRenderLimits(
        int maxTextLength,
        int maxLineItems,
        int maxPages,
        int maxOutputSizeBytes
) {
    /**
     * Default conservative production limits for SwiftDrop PDF delivery receipts.
     */
    public static final ReceiptRenderLimits DEFAULT = new ReceiptRenderLimits(
            2_000,          // Max text length for any individual text field (e.g. address, notes)
            50,             // Max financial line items
            5,              // Max PDF pages (receipts typically fit on 1 page)
            1_048_576       // Max output document size in bytes (1 MB)
    );

    public ReceiptRenderLimits {
        if (maxTextLength <= 0) throw new IllegalArgumentException("maxTextLength must be positive");
        if (maxLineItems <= 0) throw new IllegalArgumentException("maxLineItems must be positive");
        if (maxPages <= 0) throw new IllegalArgumentException("maxPages must be positive");
        if (maxOutputSizeBytes <= 0) throw new IllegalArgumentException("maxOutputSizeBytes must be positive");
    }
}
