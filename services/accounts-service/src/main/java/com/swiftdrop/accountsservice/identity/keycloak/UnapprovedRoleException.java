package com.swiftdrop.accountsservice.identity.keycloak;

/**
 * Thrown when an attempt is made to administer an unapproved role, or when caller-supplied
 * arbitrary role names are rejected by the allowlist guard.
 */
public class UnapprovedRoleException extends KeycloakAdminException {

    public UnapprovedRoleException(String message) {
        super(message);
    }
}
