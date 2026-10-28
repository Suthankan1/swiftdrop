package com.swiftdrop.accountsservice.identity.keycloak;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Server-only configuration properties for Keycloak administrative connectivity.
 * Credentials remain server-side and are never committed to version control or exposed to clients.
 */
@Component
@ConfigurationProperties(prefix = "swiftdrop.keycloak.admin")
public class KeycloakAdminProperties {

    /**
     * Keycloak base server URL (e.g. http://localhost:8090).
     */
    private String serverUrl = "http://localhost:8090";

    /**
     * Canonical target realm name (default: swiftdrop).
     */
    private String realm = "swiftdrop";

    /**
     * Administration client ID (e.g. admin-cli).
     */
    private String clientId = "admin-cli";

    /**
     * Optional client secret for service-account / client-credentials administration.
     */
    private String clientSecret;

    /**
     * Administrator username for master realm authentication.
     */
    private String username = "admin";

    /**
     * Administrator password (injected at runtime via environment variable).
     */
    private String password;

    /**
     * Connect timeout in milliseconds (default: 3000ms).
     */
    private int connectTimeoutMs = 3000;

    /**
     * Read / response timeout in milliseconds (default: 5000ms).
     */
    private int readTimeoutMs = 5000;

    public String getServerUrl() {
        return serverUrl;
    }

    public void setServerUrl(String serverUrl) {
        if (serverUrl != null && serverUrl.endsWith("/")) {
            this.serverUrl = serverUrl.substring(0, serverUrl.length() - 1);
        } else {
            this.serverUrl = serverUrl;
        }
    }

    public String getRealm() {
        return realm;
    }

    public void setRealm(String realm) {
        this.realm = realm;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getClientSecret() {
        return clientSecret;
    }

    public void setClientSecret(String clientSecret) {
        this.clientSecret = clientSecret;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public int getReadTimeoutMs() {
        return readTimeoutMs;
    }

    public void setReadTimeoutMs(int readTimeoutMs) {
        this.readTimeoutMs = readTimeoutMs;
    }

    public boolean isConfigured() {
        boolean hasPassword = password != null && !password.isBlank();
        boolean hasSecret = clientSecret != null && !clientSecret.isBlank();
        return serverUrl != null && !serverUrl.isBlank() && (hasPassword || hasSecret);
    }
}
