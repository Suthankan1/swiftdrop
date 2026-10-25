package com.swiftdrop.apigateway.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import reactor.core.publisher.Mono;

import java.util.*;

/**
 * Reactive JWT authentication converter for Keycloak access tokens at the SwiftDrop API Gateway edge.
 * Extracts and maps approved SwiftDrop business roles (CUSTOMER, MERCHANT, COURIER, DISPATCHER, ADMIN)
 * to Spring Security GrantedAuthorities with "ROLE_" prefix.
 *
 * Internal or default Keycloak roles (e.g. offline_access, uma_authorization, default-roles-swiftdrop)
 * are filtered out and never mapped.
 */
public class KeycloakReactiveJwtAuthenticationConverter implements Converter<Jwt, Mono<AbstractAuthenticationToken>> {

    public static final Set<String> APPROVED_ROLES = Set.of(
            "CUSTOMER",
            "MERCHANT",
            "COURIER",
            "DISPATCHER",
            "ADMIN"
    );

    @Override
    public Mono<AbstractAuthenticationToken> convert(Jwt jwt) {
        Set<String> approvedRoles = extractApprovedRoles(jwt);
        Collection<GrantedAuthority> authorities = approvedRoles.stream()
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
                .toList();

        return Mono.just(new JwtAuthenticationToken(jwt, authorities, jwt.getSubject()));
    }

    public static Set<String> extractApprovedRoles(Jwt jwt) {
        if (jwt == null) {
            return Collections.emptySet();
        }

        Set<String> rawRoles = new LinkedHashSet<>();

        // 1. Check custom "roles" claim array (from SwiftDrop Keycloak client/realm mapper)
        Object rolesClaim = jwt.getClaim("roles");
        if (rolesClaim instanceof Collection<?> collection) {
            for (Object item : collection) {
                if (item instanceof String roleName && !roleName.isBlank()) {
                    rawRoles.add(roleName.trim());
                }
            }
        }

        // 2. Check standard Keycloak "realm_access.roles" claim array
        Object realmAccessClaim = jwt.getClaim("realm_access");
        if (realmAccessClaim instanceof Map<?, ?> realmAccess) {
            Object realmRoles = realmAccess.get("roles");
            if (realmRoles instanceof Collection<?> collection) {
                for (Object item : collection) {
                    if (item instanceof String roleName && !roleName.isBlank()) {
                        rawRoles.add(roleName.trim());
                    }
                }
            }
        }

        // Filter strictly against approved SwiftDrop roles (case-insensitive)
        Set<String> approved = new LinkedHashSet<>();
        for (String role : rawRoles) {
            String upper = role.toUpperCase(Locale.ROOT);
            if (APPROVED_ROLES.contains(upper)) {
                approved.add(upper);
            }
        }

        return Collections.unmodifiableSet(approved);
    }
}
