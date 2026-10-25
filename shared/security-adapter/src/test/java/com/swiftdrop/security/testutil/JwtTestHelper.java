package com.swiftdrop.security.testutil;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.swiftdrop.security.jwt.AudienceValidator;
import com.swiftdrop.security.jwt.SubjectValidator;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Test helper for generating signed RSA keys and verifiable test JWTs without external identity servers.
 */
public class JwtTestHelper {

    public static final String DEFAULT_ISSUER = "http://localhost:8090/realms/swiftdrop";
    public static final String DEFAULT_USER_AUDIENCE = "swiftdrop-api";
    public static final String DEFAULT_SERVICE_AUDIENCE = "swiftdrop-internal";

    private final RSAKey trustedUserKey;
    private final RSAKey untrustedUserKey;
    private final RSAKey trustedServiceKey;
    private final RSAKey untrustedServiceKey;

    public JwtTestHelper() {
        try {
            this.trustedUserKey = new RSAKeyGenerator(2048).keyID("trusted-user-key").generate();
            this.untrustedUserKey = new RSAKeyGenerator(2048).keyID("untrusted-user-key").generate();
            this.trustedServiceKey = new RSAKeyGenerator(2048).keyID("trusted-service-key").generate();
            this.untrustedServiceKey = new RSAKeyGenerator(2048).keyID("untrusted-service-key").generate();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate test RSA keys", e);
        }
    }

    public RSAKey getTrustedUserKey() {
        return trustedUserKey;
    }

    public RSAKey getTrustedServiceKey() {
        return trustedServiceKey;
    }

    public JwtDecoder createUserJwtDecoder() {
        return createDecoder(trustedUserKey, DEFAULT_ISSUER, DEFAULT_USER_AUDIENCE);
    }

    public JwtDecoder createServiceJwtDecoder() {
        return createDecoder(trustedServiceKey, DEFAULT_ISSUER, DEFAULT_SERVICE_AUDIENCE);
    }

    public JwtDecoder createDecoder(RSAKey rsaKey, String issuer, String audience) {
        try {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(rsaKey.toRSAPublicKey()).build();
            OAuth2TokenValidator<Jwt> defaultValidator = JwtValidators.createDefaultWithIssuer(issuer);
            OAuth2TokenValidator<Jwt> audienceValidator = new AudienceValidator(audience);
            OAuth2TokenValidator<Jwt> subjectValidator = new SubjectValidator();
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(defaultValidator, audienceValidator, subjectValidator));
            return decoder;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create JwtDecoder", e);
        }
    }

    public String createSignedUserToken(String sub, Collection<String> roles) {
        return createSignedUserToken(sub, roles, DEFAULT_USER_AUDIENCE, DEFAULT_ISSUER, Instant.now().plus(1, ChronoUnit.HOURS));
    }

    public String createSignedUserToken(String sub, Collection<String> roles, String audience, String issuer, Instant expiresAt) {
        return createToken(trustedUserKey, sub, roles, audience, issuer, expiresAt, Instant.now(), null);
    }

    public String createUntrustedSignedUserToken(String sub, Collection<String> roles) {
        return createToken(untrustedUserKey, sub, roles, DEFAULT_USER_AUDIENCE, DEFAULT_ISSUER, Instant.now().plus(1, ChronoUnit.HOURS), Instant.now(), null);
    }

    public String createExpiredUserToken(String sub, Collection<String> roles) {
        Instant past = Instant.now().minus(1, ChronoUnit.HOURS);
        return createToken(trustedUserKey, sub, roles, DEFAULT_USER_AUDIENCE, DEFAULT_ISSUER, past, past.minus(1, ChronoUnit.HOURS), null);
    }

    public String createNotYetValidUserToken(String sub, Collection<String> roles) {
        Instant future = Instant.now().plus(1, ChronoUnit.HOURS);
        return createToken(trustedUserKey, sub, roles, DEFAULT_USER_AUDIENCE, DEFAULT_ISSUER, future.plus(1, ChronoUnit.HOURS), future, null);
    }

    public String createSignedServiceToken(String serviceName) {
        return createSignedServiceToken(serviceName, DEFAULT_SERVICE_AUDIENCE, DEFAULT_ISSUER, Instant.now().plus(1, ChronoUnit.HOURS), null, null);
    }

    public String createSignedServiceToken(String serviceName, String audience, String issuer, Instant expiresAt, String delegatedSub, Collection<String> delegatedRoles) {
        Map<String, Object> extra = new HashMap<>();
        extra.put("service", serviceName);
        extra.put("client_id", serviceName);
        if (delegatedSub != null) {
            extra.put("delegated_sub", delegatedSub);
        }
        if (delegatedRoles != null) {
            extra.put("delegated_roles", new ArrayList<>(delegatedRoles));
        }
        return createToken(trustedServiceKey, serviceName, Collections.emptyList(), audience, issuer, expiresAt, Instant.now(), extra);
    }

    public String createUntrustedSignedServiceToken(String serviceName) {
        Map<String, Object> extra = Map.of("service", serviceName, "client_id", serviceName);
        return createToken(untrustedServiceKey, serviceName, Collections.emptyList(), DEFAULT_SERVICE_AUDIENCE, DEFAULT_ISSUER, Instant.now().plus(1, ChronoUnit.HOURS), Instant.now(), extra);
    }

    public String createExpiredServiceToken(String serviceName) {
        Instant past = Instant.now().minus(1, ChronoUnit.HOURS);
        Map<String, Object> extra = Map.of("service", serviceName, "client_id", serviceName);
        return createToken(trustedServiceKey, serviceName, Collections.emptyList(), DEFAULT_SERVICE_AUDIENCE, DEFAULT_ISSUER, past, past.minus(1, ChronoUnit.HOURS), extra);
    }

    private String createToken(
            RSAKey key,
            String sub,
            Collection<String> roles,
            String audience,
            String issuer,
            Instant expiresAt,
            Instant issuedAt,
            Map<String, Object> additionalClaims) {
        try {
            JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
                    .subject(sub)
                    .issuer(issuer)
                    .audience(audience)
                    .issueTime(Date.from(issuedAt))
                    .notBeforeTime(Date.from(issuedAt))
                    .expirationTime(Date.from(expiresAt));

            if (roles != null && !roles.isEmpty()) {
                builder.claim("roles", new ArrayList<>(roles));
                builder.claim("realm_access", Map.of("roles", new ArrayList<>(roles)));
            }

            if (additionalClaims != null) {
                additionalClaims.forEach(builder::claim);
            }

            SignedJWT signedJWT = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
                    builder.build()
            );

            signedJWT.sign(new RSASSASigner(key.toRSAPrivateKey()));
            return signedJWT.serialize();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate signed JWT", e);
        }
    }
}
