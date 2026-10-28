package com.swiftdrop.deliveryservice.assignment.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * REST Request payload for closing an active courier assignment.
 */
public record CloseAssignmentRequest(
        @NotNull(message = "parcelId is mandatory")
        UUID parcelId,

        @NotNull(message = "expectedVersion is mandatory")
        Long expectedVersion,

        UUID assignmentId,

        String reason
) {
}

