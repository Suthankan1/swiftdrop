package com.swiftdrop.deliveryservice.assignment.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

/**
 * REST Response payload representing a courier assignment state change.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AssignmentResponse(
        UUID assignmentId,
        UUID parcelId,
        String courierSub,
        boolean active,
        Instant assignedAt,
        String assignedBy,
        Long newParcelVersion,
        Instant endedAt,
        String reason
) {
    public AssignmentResponse(
            UUID assignmentId,
            UUID parcelId,
            String courierSub,
            boolean active,
            Instant assignedAt,
            String assignedBy,
            Long newParcelVersion) {
        this(assignmentId, parcelId, courierSub, active, assignedAt, assignedBy, newParcelVersion, null, null);
    }
}
