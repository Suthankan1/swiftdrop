package com.swiftdrop.deliveryservice.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Collection;
import java.util.List;

/**
 * Maps approved SwiftDrop application roles from validated Keycloak JWTs into Spring Security GrantedAuthorities.
 */
public class KeycloakJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        List<String> approvedRoles = KeycloakRoleExtractor.extractApprovedRoles(jwt);
        Collection<GrantedAuthority> authorities = approvedRoles.stream()
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
        return new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
    }
}
