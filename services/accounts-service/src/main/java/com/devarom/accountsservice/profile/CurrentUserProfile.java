package com.devarom.accountsservice.profile;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** JSON field names/types match the frozen SPA CurrentUserProfile contract. */
public record CurrentUserProfile(UUID id, String keycloakSub, String email, String fullName,
                                 String phoneNumber, List<String> roles,
                                 MerchantProfile merchantProfile, Instant createdAt) {
    public CurrentUserProfile {
        roles = List.copyOf(roles);
    }

    /** Contract shape only. Merchant records/approval are owned by #8, not implemented here. */
    public record MerchantProfile(String merchantId, String businessName,
                                  String registrationNumber, String status) { }
}
