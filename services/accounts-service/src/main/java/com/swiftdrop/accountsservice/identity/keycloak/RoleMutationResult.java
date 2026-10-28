package com.swiftdrop.accountsservice.identity.keycloak;

import java.util.Collections;
import java.util.Set;

/**
 * Result of a controlled Keycloak role grant or revocation operation.
 *
 * @param changed     true if role membership was modified; false if it was already in the desired state (idempotent no-op).
 * @param roleName    the approved role that was mutated.
 * @param activeRoles the set of active business roles for the subject after mutation.
 */
public record RoleMutationResult(
        boolean changed,
        String roleName,
        Set<String> activeRoles
) {
    public RoleMutationResult {
        activeRoles = activeRoles != null ? Collections.unmodifiableSet(activeRoles) : Collections.emptySet();
    }
}
