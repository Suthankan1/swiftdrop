package com.swiftdrop.deliveryservice.receipt.service;

public record RenderedReceipt(
        byte[] pdfBytes,
        String trackingNumber,
        String filename
) {
}
