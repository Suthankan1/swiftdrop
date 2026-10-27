package com.swiftdrop.accountsservice.admin;

import com.swiftdrop.accountsservice.merchant.MerchantBusinessAudit;

import java.time.Instant;
import java.util.UUID;

/**
 * Projection representing an immutable merchant business audit entry.
 */
public record AdminMerchantAuditResponse(
        UUID id,
        String actor,
        String action,
        String previousStatus,
        String newStatus,
        Instant occurredAt,
        String reason,
        String outcome
) {
    public static AdminMerchantAuditResponse fromEntity(MerchantBusinessAudit audit) {
        return new AdminMerchantAuditResponse(
                audit.getId(),
                audit.getActorSub(),
                audit.getAction(),
                audit.getPreviousStatus(),
                audit.getNewStatus(),
                audit.getOccurredAt(),
                audit.getReason(),
                audit.getOutcome()
        );
    }
}
