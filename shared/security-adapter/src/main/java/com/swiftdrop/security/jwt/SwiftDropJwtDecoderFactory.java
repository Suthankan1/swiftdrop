package com.swiftdrop.security.jwt;

import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Reusable factory for configuring independent JwtDecoder instances with signature verification,
 * issuer validation, audience enforcement, and subject invariants.
 */
public final class SwiftDropJwtDecoderFactory {

    private SwiftDropJwtDecoderFactory() {}

    public static JwtDecoder createDecoder(String jwkSetUri, String issuerUri, String audience) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        OAuth2TokenValidator<Jwt> defaultValidator = JwtValidators.createDefaultWithIssuer(issuerUri);
        OAuth2TokenValidator<Jwt> audienceValidator = new AudienceValidator(audience);
        OAuth2TokenValidator<Jwt> subjectValidator = new SubjectValidator();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(defaultValidator, audienceValidator, subjectValidator));
        return decoder;
    }
}
