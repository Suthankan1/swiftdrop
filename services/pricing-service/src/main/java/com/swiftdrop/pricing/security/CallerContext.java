package com.swiftdrop.pricing.security;

import java.util.Collection;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;

/**
 * Caller security context derived from validated Spring Security Authentication (Keycloak JWT).
 */
public record CallerContext(
        String subject,
        String tenantId,
        Set<String> roles,
        boolean isAnonymous
) {
    public CallerContext {
        roles = (roles != null) ? Set.copyOf(roles) : Collections.emptySet();
    }

    public static CallerContext anonymous() {
        return new CallerContext(null, null, Collections.emptySet(), true);
    }

    public static CallerContext authenticated(String subject, String tenantId, Collection<String> roles) {
        Objects.requireNonNull(subject, "subject cannot be null for authenticated caller");
        return new CallerContext(subject.trim(), (tenantId != null && !tenantId.isBlank()) ? tenantId.trim() : null,
                roles != null ? Set.copyOf(roles) : Collections.emptySet(), false);
    }

    public boolean isPrivilegedStaff() {
        return roles.contains("ADMIN") || roles.contains("DISPATCHER");
    }
}
