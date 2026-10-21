package com.swiftdrop.accountsservice.merchant;

/**
 * Lifecycle approval states for a SwiftDrop merchant tenant.
 *
 * Distinct from authentication (Keycloak JWT):
 * - PENDING: Newly registered / provisioned merchant awaiting administrative verification.
 * - APPROVED: Verified merchant eligible for operational dispatch and order management.
 * - SUSPENDED: Temporarily halted merchant restricted from active dispatch.
 * - REJECTED: Denied merchant registration.
 */
public enum MerchantStatus {
    PENDING,
    APPROVED,
    SUSPENDED,
    REJECTED;

    public boolean isApproved() {
        return this == APPROVED;
    }
}
