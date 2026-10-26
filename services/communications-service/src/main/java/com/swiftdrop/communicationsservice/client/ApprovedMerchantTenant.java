package com.swiftdrop.communicationsservice.client;

import java.util.UUID;

/**
 * Immutable domain model representing an authenticated, verified merchant tenant from Accounts Service (IF-GRPC-01, #109).
 */
public record ApprovedMerchantTenant(
        UUID merchantId,
        String subject,
        String businessName,
        String status,
        boolean isEligible,
        boolean isActive
) {
    public boolean isApproved() {
        return "APPROVED".equalsIgnoreCase(status) && isEligible && isActive;
    }
}
