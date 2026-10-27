package com.swiftdrop.deliveryservice.assignment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * REST Request payload for reassigning a parcel to a replacement courier.
 */
public record ReassignCourierRequest(
        @NotNull(message = "parcelId is mandatory")
        UUID parcelId,

        @NotBlank(message = "replacementCourierSub is mandatory")
        String replacementCourierSub,

        @NotNull(message = "expectedVersion is mandatory")
        Long expectedVersion,

        UUID currentAssignmentId,

        String reason
) {
}

