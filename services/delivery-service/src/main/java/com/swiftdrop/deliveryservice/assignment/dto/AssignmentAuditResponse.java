package com.swiftdrop.deliveryservice.assignment.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * REST Response payload representing an immutable assignment lifecycle audit event.
 */
public record AssignmentAuditResponse(
        UUID auditId,
        UUID parcelId,
        UUID assignmentId,
        String courierSub,
        String action,
        String actorSub,
        String actorRole,
        String previousCourierSub,
        UUID previousAssignmentId,
        String reason,
        Long parcelVersion,
        Instant occurredAt
) {
}

