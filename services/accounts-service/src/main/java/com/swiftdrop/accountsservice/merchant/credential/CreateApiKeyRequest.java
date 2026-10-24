package com.swiftdrop.accountsservice.merchant.credential;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CreateApiKeyRequest(
        @NotBlank(message = "Key name is required")
        @Size(min = 1, max = 255, message = "Key name must be between 1 and 255 characters")
        String name,

        @NotEmpty(message = "At least one scope is required")
        List<String> scopes,

        @Min(value = 1, message = "Expiration must be at least 1 day")
        @Max(value = 365, message = "Expiration cannot exceed 365 days")
        Integer expiresInDays
) {
    public int resolvedExpiresInDays() {
        return (expiresInDays != null) ? expiresInDays : 90;
    }
}
