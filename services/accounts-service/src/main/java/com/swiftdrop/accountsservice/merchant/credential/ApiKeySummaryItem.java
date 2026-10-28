package com.swiftdrop.accountsservice.merchant.credential;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ApiKeySummaryItem(
        UUID keyId,
        String name,
        String keyPrefix,
        List<String> scopes,
        Instant createdAt,
        Instant expiresAt,
        boolean isRevoked
) {
    public static ApiKeySummaryItem fromEntity(MerchantApiKey entity) {
        return new ApiKeySummaryItem(
                entity.getId(),
                entity.getName(),
                entity.getKeyPrefix(),
                entity.getScopes(),
                entity.getCreatedAt(),
                entity.getExpiresAt(),
                entity.isRevoked()
        );
    }
}
