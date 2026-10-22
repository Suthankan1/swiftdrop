package com.swiftdrop.accountsservice.staff;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.UUID;

/**
 * Read-only staff business profile DTO for authenticated self-read operations.
 * Exposes operational business metadata to role-authorized frontend consumers
 * without leaking security credentials or unrelated staff records.
 */
public record StaffProfileResponse(
        @JsonProperty("profileId") UUID profileId,
        @JsonProperty("staffId") String staffId,
        @JsonProperty("keycloakSub") String keycloakSub,
        @JsonProperty("displayName") String displayName,
        @JsonProperty("email") String email,
        @JsonProperty("phoneNumber") String phoneNumber,
        @JsonProperty("role") StaffRole role,
        @JsonProperty("department") String department,
        @JsonProperty("assignedZone") String assignedZone,
        @JsonProperty("businessStatus") StaffStatus businessStatus,
        @JsonProperty("createdAt") Instant createdAt,
        @JsonProperty("updatedAt") Instant updatedAt
) {
    @JsonProperty("id")
    public UUID id() {
        return profileId;
    }

    @JsonProperty("status")
    public StaffStatus status() {
        return businessStatus;
    }
}
