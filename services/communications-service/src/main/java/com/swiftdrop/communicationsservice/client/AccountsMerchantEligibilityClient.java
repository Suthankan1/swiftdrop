package com.swiftdrop.communicationsservice.client;

import com.swiftdrop.communicationsservice.webhook.exception.MerchantNotApprovedException;
import com.swiftdrop.communicationsservice.webhook.exception.MerchantNotFoundException;

/**
 * Client interface for resolving and verifying merchant tenant eligibility from Accounts Service (IF-GRPC-01, #109).
 * Enforces server-side subject resolution, role validation, and fail-closed business lifecycle checks.
 */
public interface AccountsMerchantEligibilityClient {

    /**
     * Resolves the authoritative merchant tenant for an authenticated Keycloak subject.
     * Enforces that the merchant is in APPROVED status, active, and eligible for operations.
     *
     * @param subject Keycloak subject reference (non-blank)
     * @return ApprovedMerchantTenant containing authoritative merchant UUID and status
     * @throws MerchantNotApprovedException if merchant status is PENDING, SUSPENDED, REJECTED, or inactive
     * @throws MerchantNotFoundException if subject is not associated with any merchant tenant
     * @throws AccountsServiceUnavailableException if Accounts service is unreachable or deadline exceeded
     * @throws AccountsSecurityException if caller authorization fails
     * @throws IllegalArgumentException if subject is null or blank
     */
    ApprovedMerchantTenant resolveApprovedMerchant(String subject);
}
