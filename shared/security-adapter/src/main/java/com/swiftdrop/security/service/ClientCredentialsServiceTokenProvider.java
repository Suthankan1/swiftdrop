package com.swiftdrop.security.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Acquires, caches and renews service JWTs with the OAuth2 client_credentials grant.
 * Returns an empty token when none can be obtained, so gRPC callers fail closed.
 */
public class ClientCredentialsServiceTokenProvider implements ServiceTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(ClientCredentialsServiceTokenProvider.class);
    private static final long RENEW_BEFORE_EXPIRY_SECONDS = 30;
    private static final int DEFAULT_LIFETIME_SECONDS = 300;

    private record CachedToken(String token, Instant expiresAt) {}

    private final String tokenUri;
    private final String clientId;
    private final String clientSecret;
    private final RestClient restClient;
    private final Clock clock;
    private final AtomicReference<CachedToken> cached = new AtomicReference<>();

    public ClientCredentialsServiceTokenProvider(String tokenUri, String clientId, String clientSecret) {
        this(tokenUri, clientId, clientSecret, RestClient.create(), Clock.systemUTC());
    }

    ClientCredentialsServiceTokenProvider(String tokenUri, String clientId, String clientSecret,
                                          RestClient restClient, Clock clock) {
        this.tokenUri = tokenUri;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.restClient = restClient;
        this.clock = clock;
    }

    @Override
    public String getServiceToken() {
        CachedToken current = cached.get();
        Instant now = clock.instant();
        if (current != null && now.plusSeconds(RENEW_BEFORE_EXPIRY_SECONDS).isBefore(current.expiresAt())) {
            return current.token();
        }
        try {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("grant_type", "client_credentials");
            form.add("client_id", clientId);
            form.add("client_secret", clientSecret);
            Map<?, ?> response = restClient.post().uri(tokenUri)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(Map.class);
            if (response != null && response.get("access_token") instanceof String token && !token.isBlank()) {
                int lifetime = response.get("expires_in") instanceof Number seconds
                        ? seconds.intValue() : DEFAULT_LIFETIME_SECONDS;
                cached.set(new CachedToken(token, now.plusSeconds(lifetime)));
                return token;
            }
            log.warn("Token endpoint returned no access token for client '{}'", clientId);
        } catch (RuntimeException ex) {
            log.warn("Could not obtain service token for client '{}': {}", clientId, ex.getClass().getSimpleName());
        }
        return current != null && now.isBefore(current.expiresAt()) ? current.token() : "";
    }
}
