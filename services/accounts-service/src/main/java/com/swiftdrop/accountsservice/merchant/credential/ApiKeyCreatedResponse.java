package com.swiftdrop.accountsservice.merchant.credential;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ApiKeyCreatedResponse(
        UUID keyId,
        String name,
        String keyPrefix,
        String plaintextKey,
        List<String> scopes,
        Instant createdAt,
        Instant expiresAt
) {
}
