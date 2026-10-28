package com.swiftdrop.accountsservice.identity.keycloak;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Standard server-only Keycloak administrative credential provider.
 * Uses OAuth2 password or client_credentials grant to obtain an administrative token,
 * caching it safely in memory and refreshing prior to expiration.
 */
@Component
public class DefaultKeycloakAdminCredentialProvider implements KeycloakAdminCredentialProvider {

    private final KeycloakAdminProperties properties;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    private volatile String cachedToken;
    private volatile Instant tokenExpiry = Instant.EPOCH;
    private final Object lock = new Object();

    @Autowired
    public DefaultKeycloakAdminCredentialProvider(KeycloakAdminProperties properties) {
        this(properties, HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()))
                .build(), new ObjectMapper());
    }

    public DefaultKeycloakAdminCredentialProvider(KeycloakAdminProperties properties,
                                                 HttpClient httpClient,
                                                 ObjectMapper objectMapper) {
        this.properties = Objects.requireNonNull(properties, "KeycloakAdminProperties is required");
        this.httpClient = Objects.requireNonNull(httpClient, "HttpClient is required");
        this.objectMapper = Objects.requireNonNull(objectMapper, "ObjectMapper is required");
    }

    @Override
    public boolean isConfigured() {
        return properties.isConfigured();
    }

    @Override
    public String getAdminToken() {
        if (!isConfigured()) {
            throw new KeycloakAdminUnavailableException(
                    "Keycloak administrator credentials are not configured on Accounts Service."
            );
        }

        Instant now = Instant.now();
        if (cachedToken != null && now.isBefore(tokenExpiry)) {
            return cachedToken;
        }

        synchronized (lock) {
            now = Instant.now();
            if (cachedToken != null && now.isBefore(tokenExpiry)) {
                return cachedToken;
            }

            try {
                authenticate();
                return cachedToken;
            } catch (KeycloakAdminException e) {
                throw e;
            } catch (Exception e) {
                throw new KeycloakAdminUnavailableException("Failed to authenticate administrative client with Keycloak", e);
            }
        }
    }

    private void authenticate() throws Exception {
        boolean useClientCredentials = properties.getClientSecret() != null && !properties.getClientSecret().isBlank();
        String tokenUrl;
        Map<String, String> formParams = new HashMap<>();

        if (useClientCredentials) {
            tokenUrl = properties.getServerUrl() + "/realms/" + properties.getRealm() + "/protocol/openid-connect/token";
            formParams.put("grant_type", "client_credentials");
            formParams.put("client_id", properties.getClientId());
            formParams.put("client_secret", properties.getClientSecret());
        } else {
            tokenUrl = properties.getServerUrl() + "/realms/master/protocol/openid-connect/token";
            formParams.put("grant_type", "password");
            formParams.put("client_id", properties.getClientId());
            formParams.put("username", properties.getUsername());
            formParams.put("password", properties.getPassword() != null ? properties.getPassword() : "");
        }

        String formData = formParams.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "=" +
                          URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(tokenUrl))
                .timeout(Duration.ofMillis(properties.getReadTimeoutMs()))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(formData))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new KeycloakAdminUnavailableException(
                    "Keycloak administration authentication failed with HTTP " + response.statusCode()
            );
        }

        JsonNode root = objectMapper.readTree(response.body());
        String accessToken = root.path("access_token").asText(null);
        int expiresInSeconds = root.path("expires_in").asInt(60);

        if (accessToken == null || accessToken.isBlank()) {
            throw new KeycloakAdminUnavailableException("Keycloak token response did not contain an access_token");
        }

        this.cachedToken = accessToken;
        // Refresh 30 seconds before expiration, minimum validity 10 seconds
        int safeBuffer = Math.min(30, Math.max(5, expiresInSeconds / 2));
        this.tokenExpiry = Instant.now().plusSeconds(Math.max(10, expiresInSeconds - safeBuffer));
    }
}
