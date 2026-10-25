package com.swiftdrop.accountsservice.reconciliation;

/**
 * Policy governing when the Keycloak MERCHANT realm role is synchronized.
 *
 * Reconciles the transition contract between Issue #97 (Pending Merchant Enrollment)
 * and Issue #121 (Admin Merchant Approval/Suspension):
 *
 * - ON_APPROVAL (Default, Baseline v1.3 standard): The Keycloak MERCHANT role is granted
 *   strictly after authorized administrative business approval in Accounts. A newly enrolled
 *   PENDING merchant retains only the CUSTOMER role.
 *
 * - ON_ENROLLMENT: The Keycloak MERCHANT role is synchronized immediately upon pending enrollment,
 *   while downstream operational APIs in Accounts and Delivery independently fail closed
 *   unless status = APPROVED.
 */
public enum MerchantReconciliationTriggerPolicy {

    /**
     * Role granted only after authorized business approval.
     */
    ON_APPROVAL,

    /**
     * Role granted upon verified pending enrollment (provisional, business access gated at Accounts).
     */
    ON_ENROLLMENT;

    public boolean shouldHaveMerchantRole(com.swiftdrop.accountsservice.merchant.MerchantStatus status) {
        if (status == null) {
            return false;
        }
        return switch (status) {
            case APPROVED -> true;
            case PENDING -> this == ON_ENROLLMENT;
            case SUSPENDED, REJECTED -> false;
        };
    }
}
