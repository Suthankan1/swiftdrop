package com.swiftdrop.pricing.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Resolves verified caller context strictly from SecurityContextHolder.
 * Untrusted headers or client-supplied parameters are completely ignored.
 */
@Component
public class SecurityContextCallerResolver {

    public CallerContext resolveCurrentCaller() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwtAuth && jwtAuth.isAuthenticated()) {
            Jwt jwt = jwtAuth.getToken();
            String subject = jwt.getSubject();
            if (subject != null && !subject.isBlank()) {
                String tenantId = extractTenantId(jwt);
                Set<String> roles = jwtAuth.getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority)
                        .map(a -> a.startsWith("ROLE_") ? a.substring(5) : a)
                        .collect(Collectors.toSet());
                return CallerContext.authenticated(subject, tenantId, roles);
            }
        }
        return CallerContext.anonymous();
    }

    private String extractTenantId(Jwt jwt) {
        Object tenantClaim = jwt.getClaim("tenant_id");
        if (tenantClaim instanceof String s && !s.isBlank()) {
            return s.trim();
        }
        Object merchantIdClaim = jwt.getClaim("merchant_id");
        if (merchantIdClaim instanceof String s && !s.isBlank()) {
            return s.trim();
        }
        return null;
    }
}
