package com.swiftdrop.deliveryservice.booking;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Request payload for POST /api/v1/merchant/parcels.
 * Conforms strictly to openapi-delivery.yaml and Issue #129 specifications.
 */
public record MerchantBookParcelRequest(
        @NotNull(message = "quoteId is required")
        UUID quoteId,

        @NotBlank(message = "pickupAddress cannot be blank")
        @Size(max = 2000, message = "pickupAddress cannot exceed 2000 characters")
        String pickupAddress,

        @NotBlank(message = "dropoffAddress cannot be blank")
        @Size(max = 2000, message = "dropoffAddress cannot exceed 2000 characters")
        String dropoffAddress,

        @NotBlank(message = "recipientName cannot be blank")
        @Size(max = 255, message = "recipientName cannot exceed 255 characters")
        String recipientName,

        @NotBlank(message = "recipientPhone cannot be blank")
        @Size(max = 50, message = "recipientPhone cannot exceed 50 characters")
        @Pattern(regexp = "^\\+?[0-9\\s\\-\\(\\)]{7,25}$", message = "recipientPhone must be a valid phone number")
        String recipientPhone,

        @NotBlank(message = "merchantReference cannot be blank")
        @Size(max = 255, message = "merchantReference cannot exceed 255 characters")
        String merchantReference,

        @Size(max = 2000, message = "deliveryNotes cannot exceed 2000 characters")
        String deliveryNotes
) {
}
