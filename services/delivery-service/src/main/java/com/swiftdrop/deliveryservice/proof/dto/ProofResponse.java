package com.swiftdrop.deliveryservice.proof.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Result payload returned upon successful proof metadata ingestion.
 * Matches OpenAPI schema ProofResponse.
 */
public record ProofResponse(
        UUID proofId,
        UUID parcelId,
        String uploadedBySub,
        Instant uploadedAt
) {
}
