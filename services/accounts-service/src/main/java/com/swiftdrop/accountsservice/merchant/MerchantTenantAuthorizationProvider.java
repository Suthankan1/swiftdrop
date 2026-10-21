package com.swiftdrop.accountsservice.merchant;

import com.swiftdrop.accountsservice.identity.TrustedIdentity;

import java.util.Optional;
import java.util.UUID;

/**
 * Provider interface for tenant authorization decisions.
 * Enforces least-privilege tenant isolation and prevents unauthorized cross-tenant operations.
 */
public interface MerchantTenantAuthorizationProvider {

    /**
     * Evaluates whether the authenticated identity is authorized to access the specified merchant tenant.
     *
     * @param identity the verified caller identity from SecurityContext
     * @param targetMerchantId the requested merchant tenant ID
     * @return TenantAuthorizationDecision describing whether access is allowed and the reason if denied
     */
    TenantAuthorizationDecision authorizeTenantAccess(TrustedIdentity identity, UUID targetMerchantId);

    /**
     * Enforces tenant access.
     * Throws TenantAccessDeniedException if identity is not authorized for targetMerchantId.
     * Throws MerchantNotFoundException if targetMerchantId does not exist.
     *
     * @param identity the verified caller identity
     * @param targetMerchantId the target merchant tenant UUID
     */
    void requireTenantAccess(TrustedIdentity identity, UUID targetMerchantId);

    /**
     * Enforces BOTH tenant access AND that the merchant status is APPROVED.
     * Throws TenantAccessDeniedException if unauthorized.
     * Throws MerchantNotFoundException if not found.
     * Throws MerchantNotApprovedException if merchant is PENDING, SUSPENDED, or REJECTED.
     *
     * @param identity the verified caller identity
     * @param targetMerchantId the target merchant tenant UUID
     */
    void requireApprovedTenantAccess(TrustedIdentity identity, UUID targetMerchantId);

    /**
     * Resolves the merchant tenant record bound to the authenticated identity, if one exists.
     *
     * @param identity the verified caller identity
     * @return Optional containing the MerchantRecord, or empty if caller has no merchant binding
     */
    Optional<MerchantRecord> resolveMerchantForIdentity(TrustedIdentity identity);

    /**
     * Resolves a merchant tenant record by its UUID.
     *
     * @param merchantId the merchant UUID
     * @return Optional containing the MerchantRecord, or empty if not found
     */
    Optional<MerchantRecord> resolveMerchantById(UUID merchantId);
}
