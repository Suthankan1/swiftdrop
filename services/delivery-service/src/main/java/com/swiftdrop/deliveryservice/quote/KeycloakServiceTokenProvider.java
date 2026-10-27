package com.swiftdrop.deliveryservice.quote;

import com.swiftdrop.security.service.ServiceTokenProvider;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Service token provider that acquires and caches OAuth2 machine-to-machine JWT tokens
 * from Keycloak via the client_credentials grant.
 */
public class KeycloakServiceTokenProvider implements ServiceTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(KeycloakServiceTokenProvider.class);

    private final String staticToken;
    private final String tokenUri;
    private final String clientId;
    private final String clientSecret;
    private final RestClient restClient;

    private final AtomicReference<CachedToken> cachedToken = new AtomicReference<>();

    private record CachedToken(String token, Instant expiresAt) {}

    public KeycloakServiceTokenProvider(
            @Value("${SWIFTDROP_DELIVERY_SERVICE_JWT:${swiftdrop.delivery.service-token:}}") String staticToken,
            @Value("${KEYCLOAK_TOKEN_URI:${swiftdrop.keycloak.token-uri:}}") String tokenUri,
            @Value("${KEYCLOAK_JWKS_URI:${swiftdrop.keycloak.jwks-uri:${spring.security.oauth2.resourceserver.jwt.jwk-set-uri:}}}") String jwksUri,
            @Value("${KEYCLOAK_ISSUER_URI:${swiftdrop.keycloak.issuer-uri:${spring.security.oauth2.resourceserver.jwt.issuer-uri:http://localhost:8090/realms/swiftdrop}}}") String issuerUri,
            @Value("${SWIFTDROP_DELIVERY_CLIENT_ID:delivery-service}") String clientId,
            @Value("${SWIFTDROP_DELIVERY_CLIENT_SECRET:delivery-service-secret-internal}") String clientSecret) {

        this.staticToken = staticToken != null ? staticToken.trim() : "";
        this.clientId = clientId != null ? clientId.trim() : "delivery-service";
        this.clientSecret = clientSecret != null ? clientSecret.trim() : "delivery-service-secret-internal";

        if (tokenUri != null && !tokenUri.isBlank()) {
            this.tokenUri = tokenUri.trim();
        } else if (jwksUri != null && jwksUri.contains("/protocol/openid-connect/certs")) {
            this.tokenUri = jwksUri.replace("/protocol/openid-connect/certs", "/protocol/openid-connect/token");
        } else {
            this.tokenUri = (issuerUri != null && !issuerUri.isBlank() ? issuerUri.trim() : "http://localhost:8090/realms/swiftdrop")
                    + "/protocol/openid-connect/token";
        }

        this.restClient = RestClient.builder().build();
    }

    @PostConstruct
    public void warmup() {
        try {
            String token = getServiceToken();
            if (token != null && !token.isBlank()) {
                log.info("Keycloak service token pre-fetched successfully for client '{}'", clientId);
            }
        } catch (Exception ex) {
            log.warn("Keycloak service token pre-fetch deferred: {}", ex.getMessage());
        }
    }

    @Override
    public String getServiceToken() {
        if (!staticToken.isBlank()) {
            return staticToken;
        }

        CachedToken current = cachedToken.get();
        if (current != null && Instant.now().plusSeconds(30).isBefore(current.expiresAt())) {
            return current.token();
        }

        try {
            MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
            formData.add("grant_type", "client_credentials");
            formData.add("client_id", clientId);
            formData.add("client_secret", clientSecret);

            Map<?, ?> response = restClient.post()
                    .uri(tokenUri)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(formData)
                    .retrieve()
                    .body(Map.class);

            if (response != null && response.containsKey("access_token")) {
                String token = (String) response.get("access_token");
                int expiresIn = response.containsKey("expires_in")
                        ? ((Number) response.get("expires_in")).intValue()
                        : 300;
                Instant expiresAt = Instant.now().plusSeconds(expiresIn);
                cachedToken.set(new CachedToken(token, expiresAt));
                return token;
            }
        } catch (Exception ex) {
            log.warn("Failed to retrieve service token from Keycloak at {}: {}", tokenUri, ex.getMessage());
            if (current != null && Instant.now().isBefore(current.expiresAt())) {
                return current.token();
            }
        }

        return "";
    }
}

