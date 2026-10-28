package com.swiftdrop.graphql.security;

import java.util.Map;
import java.util.Set;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** Role gate only. Owning providers must independently verify tenant, lifecycle and resource scope. */
public final class GraphqlFieldPolicy {
    private static final Map<String, Set<String>> ROLES = Map.of(
        "customerDashboard", Set.of("CUSTOMER"), "merchantDashboard", Set.of("MERCHANT"),
        "courierDashboard", Set.of("COURIER"), "dispatcherDashboard", Set.of("DISPATCHER", "ADMIN"),
        "adminDashboard", Set.of("ADMIN"), "deliveryStats", Set.of("DISPATCHER", "ADMIN"));

    public void check(String field, JwtAuthenticationToken actor) {
        if (actor == null || !actor.isAuthenticated() || actor.getToken().getSubject() == null
                || !ROLES.getOrDefault(field, Set.of()).stream().anyMatch(role ->
                    actor.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_" + role)))) {
            throw new AccessDeniedException("Query access denied");
        }
    }
}
