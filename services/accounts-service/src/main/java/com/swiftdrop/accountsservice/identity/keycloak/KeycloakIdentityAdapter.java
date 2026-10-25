package com.swiftdrop.accountsservice.identity.keycloak;

import java.util.Optional;
import java.util.Set;

/**
 * Controlled, server-only Keycloak identity administration adapter interface.
 *
 * Provides narrowly scoped operations for inspecting identity existence,
 * reading current role assignments, granting approved roles, and revoking roles
 * strictly in accordance with approved business lifecycle transitions.
 */
public interface KeycloakIdentityAdapter {

    /**
     * Checks if an identity with the given exact Keycloak subject UUID exists in the realm.
     *
     * @param subject the exact Keycloak subject UUID.
     * @return true if the identity exists; false otherwise.
     */
    boolean identityExists(String subject);

    /**
     * Resolves verified identity metadata by exact Keycloak subject UUID.
     *
     * @param subject the exact Keycloak subject UUID.
     * @return Optional containing the identity details, or empty if not found.
     */
    Optional<KeycloakUserIdentity> getUserIdentity(String subject);

    /**
     * Retrieves the set of active realm roles for the target subject, filtered to approved roles.
     *
     * @param subject the exact Keycloak subject UUID.
     * @return set of active role names.
     */
    Set<String> getUserRoles(String subject);

    /**
     * Grants an approved role to the target user.
     * Fails closed if the role is not in the approved role allowlist.
     *
     * @param subject  the exact Keycloak subject UUID.
     * @param roleName the approved role name (e.g. COURIER, DISPATCHER, ADMIN, MERCHANT).
     * @return RoleMutationResult indicating whether state was changed.
     */
    RoleMutationResult grantRole(String subject, String roleName);

    /**
     * Revokes an approved role from the target user.
     * Fails closed if the role is not in the approved role allowlist.
     *
     * @param subject  the exact Keycloak subject UUID.
     * @param roleName the approved role name.
     * @return RoleMutationResult indicating whether state was changed.
     */
    RoleMutationResult revokeRole(String subject, String roleName);

    /**
     * Idempotently reconciles role assignment for a subject, verifying the post-operation state.
     *
     * @param subject        the exact Keycloak subject UUID.
     * @param roleName       the approved role name.
     * @param shouldHaveRole true if the role must be present; false if it must be absent.
     * @return ReconciliationResult containing structured outcome and audit evidence.
     */
    ReconciliationResult reconcileRole(String subject, String roleName, boolean shouldHaveRole);
}
