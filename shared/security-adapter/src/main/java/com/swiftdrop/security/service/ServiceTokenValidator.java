package com.swiftdrop.security.service;

import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Optional;

/**
 * Interface for cryptographically validating internal service-to-service JWTs (x-swiftdrop-service-token).
 */
public interface ServiceTokenValidator {

    record Result(boolean isValid, SwiftDropServiceIdentity serviceIdentity, String errorMessage, Jwt jwt) {
        public static Result success(SwiftDropServiceIdentity serviceIdentity, Jwt jwt) {
            return new Result(true, serviceIdentity, null, jwt);
        }

        public static Result failure(String errorMessage) {
            return new Result(false, null, errorMessage, null);
        }
    }

    Result validate(String tokenString);
}
