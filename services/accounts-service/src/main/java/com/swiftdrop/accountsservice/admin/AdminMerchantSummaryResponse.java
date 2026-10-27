package com.swiftdrop.accountsservice.admin;

import com.swiftdrop.accountsservice.merchant.Merchant;
import com.swiftdrop.accountsservice.merchant.MerchantStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Summary DTO for paged administrative merchant review reads.
 * Aligns with the frontend MerchantReviewSummary contract while omitting sensitive credentials.
 */
public record AdminMerchantSummaryResponse(
        UUID id,
        String businessName,
        String registrationNumber,
        MerchantStatus status,
        Instant submittedAt,
        String reviewToken
) {
    public static AdminMerchantSummaryResponse fromEntity(Merchant entity) {
        return new AdminMerchantSummaryResponse(
                entity.getId(),
                entity.getBusinessName(),
                entity.getRegistrationNumber(),
                entity.getStatus(),
                entity.getCreatedAt(),
                String.valueOf(entity.getVersion() != null ? entity.getVersion() : 0L)
        );
    }
}
