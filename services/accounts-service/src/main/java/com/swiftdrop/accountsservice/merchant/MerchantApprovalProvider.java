package com.swiftdrop.accountsservice.merchant;

import java.util.Optional;
import java.util.UUID;

/**
 * Provider interface to resolve merchant approval state.
 * Enables authorized components to determine whether a merchant is approved for business operations.
 */
public interface MerchantApprovalProvider {

    /**
     * Resolves the approval state of a merchant tenant by its merchant ID.
     *
     * @param merchantId the unique tenant UUID
     * @return Optional containing the MerchantStatus, or empty if merchant does not exist
     */
    Optional<MerchantStatus> getApprovalStatus(UUID merchantId);

    /**
     * Resolves the approval state of a merchant tenant associated with a Keycloak subject.
     *
     * @param keycloakSub the verified subject claim from token
     * @return Optional containing the MerchantStatus, or empty if no merchant is bound
     */
    Optional<MerchantStatus> getApprovalStatusBySubject(String keycloakSub);

    /**
     * Returns true if and only if the merchant exists and its approval status is APPROVED.
     *
     * @param merchantId the unique tenant UUID
     * @return true if approved, false otherwise (pending, suspended, rejected, or missing)
     */
    boolean isApproved(UUID merchantId);

    /**
     * Returns true if and only if the merchant bound to this subject exists and its status is APPROVED.
     *
     * @param keycloakSub the verified subject claim
     * @return true if approved, false otherwise
     */
    boolean isApproved(String keycloakSub);
}
