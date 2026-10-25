package com.swiftdrop.accountsservice.identity.keycloak;

/**
 * Thrown when a target Keycloak identity (subject UUID) does not exist in the realm.
 */
public class IdentityNotFoundException extends KeycloakAdminException {

    private final String subject;

    public IdentityNotFoundException(String subject) {
        super("Keycloak identity not found for subject: " + subject);
        this.subject = subject;
    }

    public String getSubject() {
        return subject;
    }
}
