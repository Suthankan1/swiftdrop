package com.swiftdrop.pricing.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Tests caller resolution after authentication; does not replace JWT/filter-chain tests. */
class SecurityContextCallerResolverTest {

    private final SecurityContextCallerResolver resolver = new SecurityContextCallerResolver();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void missingAuthenticationCannotSupplyPrivilegedIdentity() {
        assertThat(resolver.resolveCurrentCaller().isAnonymous()).isTrue();
    }

    @Test
    void nonJwtAuthenticationWithAdminAuthorityCannotSupplyPrivilegedIdentity() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "untrusted-admin", "", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        assertThat(resolver.resolveCurrentCaller().isAnonymous()).isTrue();
    }

    @Test
    void missingJwtSubjectCannotSupplyPrivilegedIdentity() {
        Jwt jwt = Jwt.withTokenValue("synthetic-test-value").header("alg", "RS256")
                .claim("tenant_id", "tenant-1").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(
                jwt, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        assertThat(resolver.resolveCurrentCaller().isAnonymous()).isTrue();
    }

    @Test
    void resolvesSubjectAndAuthoritiesOnlyFromAuthenticatedJwtContext() {
        Jwt jwt = Jwt.withTokenValue("synthetic-test-value").header("alg", "RS256")
                .subject("admin-123").claim("tenant_id", "tenant-1").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(
                jwt, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        CallerContext caller = resolver.resolveCurrentCaller();
        assertThat(caller.isAnonymous()).isFalse();
        assertThat(caller.subject()).isEqualTo("admin-123");
        assertThat(caller.tenantId()).isEqualTo("tenant-1");
        assertThat(caller.roles()).containsExactly("ADMIN");
    }

    @Test
    void unauthenticatedJwtContextCannotSupplyPrivilegedIdentity() {
        Jwt jwt = Jwt.withTokenValue("synthetic-test-value").header("alg", "RS256")
                .subject("admin-123").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        assertThat(resolver.resolveCurrentCaller().isAnonymous()).isTrue();
    }
}
