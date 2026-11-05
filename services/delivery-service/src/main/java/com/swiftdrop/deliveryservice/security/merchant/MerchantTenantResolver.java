package com.swiftdrop.deliveryservice.security.merchant;

import java.util.Optional;
import java.util.UUID;

/**
 * Authoritative boundary resolving and verifying merchant tenant identity for authenticated principals.
 */
public interface MerchantTenantResolver {

    /**
     * Resolves the merchant tenant UUID bound to the given authenticated Keycloak subject.
     * Enforces active and approved status with Accounts service.
     *
     * @param subject Keycloak subject UUID string
     * @return Optional containing verified merchant tenant UUID, or empty if ineligible/not found
     */
    Optional<UUID> resolveMerchantTenant(String subject);
}
