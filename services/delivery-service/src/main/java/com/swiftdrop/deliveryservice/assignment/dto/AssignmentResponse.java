package com.swiftdrop.deliveryservice.assignment.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Response payload emitted upon successful courier assignment.
 *
 * Implements REST contract schema AssignmentResponse in openapi-delivery.yaml.
 */
public record AssignmentResponse(
        UUID assignmentId,
        UUID parcelId,
        String courierSub,
        boolean active,
        Instant assignedAt,
        String assignedBy,
        Long newParcelVersion
) {
}
