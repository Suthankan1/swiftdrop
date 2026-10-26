package com.swiftdrop.deliveryservice.proof.dto;

import jakarta.validation.constraints.Size;

/**
 * Command payload for ingesting delivery proof metadata.
 * Matches OpenAPI schema UploadProofRequest.
 */
public record UploadProofRequest(
        @Size(max = 128, message = "recipientName cannot exceed 128 characters")
        String recipientName,

        @Size(max = 128, message = "photoObjectKey cannot exceed 128 characters")
        String photoObjectKey,

        @Size(max = 512, message = "deliveryNote cannot exceed 512 characters")
        String deliveryNote
) {
}
