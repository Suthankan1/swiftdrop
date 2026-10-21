package com.swiftdrop.accountsservice.security;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Validates that the access JWT contains an opaque non-blank subject claim that satisfies profile invariants.
 */
public class SubjectValidator implements OAuth2TokenValidator<Jwt> {

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        String subject = jwt.getSubject();
        if (subject != null && !subject.isBlank() && subject.length() <= 255 && subject.indexOf('\0') < 0) {
            return OAuth2TokenValidatorResult.success();
        }
        OAuth2Error error = new OAuth2Error(
                OAuth2ErrorCodes.INVALID_TOKEN,
                "The required subject claim ('sub') is missing or invalid in the JWT",
                null
        );
        return OAuth2TokenValidatorResult.failure(error);
    }
}
