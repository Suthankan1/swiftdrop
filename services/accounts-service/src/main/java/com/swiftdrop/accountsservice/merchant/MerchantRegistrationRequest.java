package com.swiftdrop.accountsservice.merchant;

import tools.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Immutable DTO for merchant enrollment command.
 * Strictly ignores and rejects any unknown or client-supplied privileged/ownership fields
 * in accordance with the strict JSON input security policy.
 */
@JsonDeserialize(using = MerchantRegistrationRequestDeserializer.class)
public record MerchantRegistrationRequest(
        @NotBlank(message = "Business name is required")
        @Size(min = 3, max = 255, message = "Business name must be between 3 and 255 characters")
        String businessName,

        @NotBlank(message = "Registration number is required")
        @Size(min = 3, max = 100, message = "Registration number must be between 3 and 100 characters")
        String registrationNumber
) {
    public MerchantRegistrationRequest {
        if (businessName != null && businessName.trim().length() < 3) {
            throw new IllegalArgumentException("Business name must have at least 3 non-whitespace characters");
        }
        if (registrationNumber != null && registrationNumber.trim().length() < 3) {
            throw new IllegalArgumentException("Registration number must have at least 3 non-whitespace characters");
        }
    }
}
