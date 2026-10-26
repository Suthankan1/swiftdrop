package com.swiftdrop.deliveryservice.proof.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Detailed metadata response for authorized proof inspection.
 * Zero private URLs or storage credentials exposed.
 */
public record ProofDetailResponse(
        UUID proofId,
        UUID parcelId,
        String objectReference,
        String evidenceType,
        String contentType,
        Long contentLength,
        String recipientName,
        String deliveryNote,
        String uploadedBySub,
        Instant uploadedAt
) {
}
