package com.swiftdrop.accountsservice.identity.keycloak;

/**
 * Value record representing verified Keycloak user identity attributes.
 */
public record KeycloakUserIdentity(
        String subject,
        String username,
        String email,
        boolean enabled
) {
    public KeycloakUserIdentity {
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("Subject must not be null or blank");
        }
    }
}
