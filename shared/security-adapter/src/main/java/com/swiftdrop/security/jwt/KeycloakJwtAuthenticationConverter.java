package com.swiftdrop.security.jwt;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Collection;
import java.util.Set;

/**
 * Maps approved SwiftDrop application roles from validated Keycloak JWTs into Spring Security GrantedAuthorities,
 * binding an immutable SwiftDropPrincipal into authentication details.
 */
public class KeycloakJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Set<String> approvedRoles = KeycloakRoleExtractor.extractApprovedRoles(jwt);
        Collection<GrantedAuthority> authorities = approvedRoles.stream()
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
                .toList();

        String preferredUsername = jwt.getClaimAsString("preferred_username");
        String email = jwt.getClaimAsString("email");

        SwiftDropPrincipal principal = new SwiftDropPrincipal(
                jwt.getSubject(),
                approvedRoles,
                preferredUsername != null ? preferredUsername : jwt.getSubject(),
                email,
                jwt.getClaims()
        );

        JwtAuthenticationToken token = new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
        token.setDetails(principal);
        return token;
    }
}
