package com.swiftdrop.security.service;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Standard cryptographic implementation of ServiceTokenValidator backed by Spring Security's JwtDecoder.
 */
public class JwtServiceTokenValidator implements ServiceTokenValidator {

    public static final String DEFAULT_SERVICE_AUDIENCE = "swiftdrop-internal";

    private final JwtDecoder jwtDecoder;
    private final String expectedAudience;
    private final String expectedIssuer;

    public JwtServiceTokenValidator(JwtDecoder jwtDecoder, String expectedAudience, String expectedIssuer) {
        this.jwtDecoder = Objects.requireNonNull(jwtDecoder, "jwtDecoder must not be null");
        this.expectedAudience = expectedAudience != null ? expectedAudience : DEFAULT_SERVICE_AUDIENCE;
        this.expectedIssuer = expectedIssuer;
    }

    public JwtServiceTokenValidator(JwtDecoder jwtDecoder) {
        this(jwtDecoder, DEFAULT_SERVICE_AUDIENCE, null);
    }

    @Override
    public Result validate(String tokenString) {
        if (tokenString == null || tokenString.isBlank()) {
            return Result.failure("Missing service authentication token");
        }

        try {
            Jwt jwt = jwtDecoder.decode(tokenString);

            // 1. Validate temporal validity
            Instant now = Instant.now();
            Instant expiresAt = jwt.getExpiresAt();
            if (expiresAt == null || now.isAfter(expiresAt)) {
                return Result.failure("Service token has expired");
            }

            Instant notBefore = jwt.getNotBefore();
            if (notBefore != null && now.isBefore(notBefore)) {
                return Result.failure("Service token is not yet valid (nbf)");
            }

            // 2. Validate audience
            if (expectedAudience != null) {
                List<String> audiences = jwt.getAudience();
                if (audiences == null || !audiences.contains(expectedAudience)) {
                    return Result.failure("Service token audience mismatch: expected '" + expectedAudience + "'");
                }
            }

            // 3. Validate issuer if configured
            if (expectedIssuer != null) {
                String issuer = jwt.getIssuer() != null ? jwt.getIssuer().toString() : null;
                if (!expectedIssuer.equals(issuer)) {
                    return Result.failure("Service token issuer mismatch: untrusted issuer '" + issuer + "'");
                }
            }

            // 4. Extract verified service identity from "service" or "sub" or "client_id"
            String serviceName = jwt.getClaimAsString("service");
            if (serviceName == null || serviceName.isBlank()) {
                serviceName = jwt.getClaimAsString("client_id");
            }
            if (serviceName == null || serviceName.isBlank()) {
                serviceName = jwt.getSubject();
            }

            Optional<SwiftDropServiceIdentity> serviceIdentity = SwiftDropServiceIdentity.fromServiceName(serviceName);
            if (serviceIdentity.isEmpty()) {
                return Result.failure("Unknown or unauthorized service caller: '" + serviceName + "'");
            }

            return Result.success(serviceIdentity.get(), jwt);

        } catch (JwtException ex) {
            return Result.failure("Invalid service token: " + ex.getMessage());
        } catch (Exception ex) {
            return Result.failure("Service token validation failed: " + ex.getMessage());
        }
    }
}
