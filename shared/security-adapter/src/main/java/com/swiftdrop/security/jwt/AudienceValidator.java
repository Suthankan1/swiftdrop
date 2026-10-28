package com.swiftdrop.security.jwt;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;
import java.util.Objects;

/**
 * Validates that the access JWT contains the required SwiftDrop API audience claim ("swiftdrop-api" by default).
 */
public class AudienceValidator implements OAuth2TokenValidator<Jwt> {

    public static final String DEFAULT_AUDIENCE = "swiftdrop-api";

    private final String expectedAudience;

    public AudienceValidator(String expectedAudience) {
        this.expectedAudience = Objects.requireNonNull(expectedAudience, "expectedAudience must not be null");
    }

    public AudienceValidator() {
        this(DEFAULT_AUDIENCE);
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        List<String> audiences = jwt.getAudience();
        if (audiences != null && audiences.contains(expectedAudience)) {
            return OAuth2TokenValidatorResult.success();
        }
        OAuth2Error error = new OAuth2Error(
                OAuth2ErrorCodes.INVALID_TOKEN,
                "The required audience '" + expectedAudience + "' is missing or invalid in the JWT",
                null
        );
        return OAuth2TokenValidatorResult.failure(error);
    }

    public String getExpectedAudience() {
        return expectedAudience;
    }
}
