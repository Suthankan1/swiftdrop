package com.swiftdrop.accountsservice.admin;

import java.time.Instant;
import java.util.UUID;

public record AdminUserSummaryResponse(
        UUID profileId,
        String keycloakSub,
        String email,
        String fullName,
        Instant createdAt
) { }
