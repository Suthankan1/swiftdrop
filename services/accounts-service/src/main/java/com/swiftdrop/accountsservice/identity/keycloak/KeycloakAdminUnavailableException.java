package com.swiftdrop.accountsservice.identity.keycloak;

/**
 * Thrown when Keycloak administration endpoint is unreachable, timed out, or administrative
 * authorization credentials are invalid. Fails closed.
 */
public class KeycloakAdminUnavailableException extends KeycloakAdminException {

    public KeycloakAdminUnavailableException(String message) {
        super(message);
    }

    public KeycloakAdminUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
