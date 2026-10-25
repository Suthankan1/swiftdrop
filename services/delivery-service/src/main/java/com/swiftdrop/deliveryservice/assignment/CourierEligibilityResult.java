package com.swiftdrop.deliveryservice.assignment;

/**
 * Result of evaluating courier business eligibility with Accounts Service.
 */
public record CourierEligibilityResult(
        String courierSub,
        boolean eligible,
        boolean active,
        String rejectionReason
) {
    public boolean isApproved() {
        return eligible && active;
    }
}
