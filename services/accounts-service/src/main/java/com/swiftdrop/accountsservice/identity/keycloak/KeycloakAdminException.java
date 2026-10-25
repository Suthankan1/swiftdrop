package com.swiftdrop.accountsservice.identity.keycloak;

/**
 * Base exception for Keycloak administration adapter errors.
 */
public abstract class KeycloakAdminException extends RuntimeException {

    protected KeycloakAdminException(String message) {
        super(message);
    }

    protected KeycloakAdminException(String message, Throwable cause) {
        super(message, cause);
    }
}
