package com.swiftdrop.accountsservice.identity.keycloak;

/**
 * Server-only provider of administrative access tokens for Keycloak Admin API communication.
 * Keeps administrative credentials isolated to the backend server runtime.
 */
public interface KeycloakAdminCredentialProvider {

    /**
     * Retrieves an administrative bearer access token for Keycloak Admin REST API calls.
     * Caches valid tokens and refreshes before expiration.
     *
     * @return non-empty bearer access token string.
     * @throws KeycloakAdminUnavailableException if authentication fails or credentials are unconfigured.
     */
    String getAdminToken();

    /**
     * Checks whether administrative credentials have been configured.
     */
    boolean isConfigured();
}
