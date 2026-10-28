package com.swiftdrop.deliveryservice.assignment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Request payload for dispatching a courier to an unassigned parcel in CREATED state.
 *
 * Implements REST contract schema AssignCourierRequest in openapi-delivery.yaml.
 */
public record AssignCourierRequest(
        @NotNull(message = "parcelId is required")
        UUID parcelId,

        @NotBlank(message = "courierSub is required")
        String courierSub,

        @NotNull(message = "expectedVersion is required")
        Long expectedVersion
) {
}
