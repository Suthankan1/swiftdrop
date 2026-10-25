package com.swiftdrop.accountsservice.merchant;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Authoritative response DTO for merchant enrollment and profile queries.
 * Conforms to the MerchantProfileResponse schema defined in contracts/rest/openapi-accounts.yaml.
 */
public record MerchantProfileResponse(
        UUID id,
        String keycloakSub,
        String businessName,
        String registrationNumber,
        MerchantStatus status,
        Instant createdAt,
        Instant updatedAt
) {
    public MerchantProfileResponse {
        Objects.requireNonNull(id, "Merchant ID is required");
        Objects.requireNonNull(keycloakSub, "Keycloak subject is required");
        Objects.requireNonNull(businessName, "Business name is required");
        Objects.requireNonNull(registrationNumber, "Registration number is required");
        Objects.requireNonNull(status, "Status is required");
        Objects.requireNonNull(createdAt, "Created timestamp is required");
        Objects.requireNonNull(updatedAt, "Updated timestamp is required");
    }

    public static MerchantProfileResponse fromEntity(Merchant entity) {
        Objects.requireNonNull(entity, "Merchant entity is required");
        return new MerchantProfileResponse(
                entity.getId(),
                entity.getKeycloakSub(),
                entity.getBusinessName(),
                entity.getRegistrationNumber(),
                entity.getStatus(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
