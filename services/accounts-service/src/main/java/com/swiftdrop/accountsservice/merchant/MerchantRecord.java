package com.swiftdrop.accountsservice.merchant;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable DTO representing a merchant tenant.
 */
public record MerchantRecord(
        UUID id,
        UUID accountProfileId,
        String keycloakSub,
        String businessName,
        String registrationNumber,
        MerchantStatus status,
        Instant createdAt
) {
    public static MerchantRecord fromEntity(Merchant entity) {
        return new MerchantRecord(
                entity.getId(),
                entity.getAccountProfileId(),
                entity.getKeycloakSub(),
                entity.getBusinessName(),
                entity.getRegistrationNumber(),
                entity.getStatus(),
                entity.getCreatedAt()
        );
    }
}
