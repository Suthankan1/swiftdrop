package com.swiftdrop.accountsservice.admin;

import java.time.Instant;
import java.util.UUID;

public record AdminUserSummaryResponse(
        UUID profileId,
        String keycloakSub,
        String email,
        String fullName,
        Instant createdAt,
        /** Sign-in state from Keycloak; null when the identity provider could not be read. */
        Boolean enabled
) {
    public AdminUserSummaryResponse(UUID profileId, String keycloakSub, String email, String fullName, Instant createdAt) {
        this(profileId, keycloakSub, email, fullName, createdAt, null);
    }
}
