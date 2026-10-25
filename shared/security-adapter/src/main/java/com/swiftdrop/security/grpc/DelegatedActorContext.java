package com.swiftdrop.security.grpc;

import com.swiftdrop.security.jwt.SwiftDropRoles;
import com.swiftdrop.security.service.SwiftDropServiceIdentity;

import java.io.Serializable;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;

/**
 * Validated context representing an authenticated human actor on whose behalf an internal gRPC call is made.
 */
public record DelegatedActorContext(
        String actorSub,
        Set<String> actorRoles,
        SwiftDropServiceIdentity delegatingService,
        String tenantId
) implements Serializable {

    public DelegatedActorContext {
        Objects.requireNonNull(actorSub, "actorSub must not be null");
        Objects.requireNonNull(delegatingService, "delegatingService must not be null");
        actorRoles = actorRoles != null ? Collections.unmodifiableSet(actorRoles) : Collections.emptySet();
    }

    public DelegatedActorContext(String actorSub, Set<String> actorRoles, SwiftDropServiceIdentity delegatingService) {
        this(actorSub, actorRoles, delegatingService, null);
    }

    public boolean hasRole(String role) {
        if (role == null) {
            return false;
        }
        return actorRoles.contains(role.trim().toUpperCase());
    }

    public boolean isAdmin() {
        return hasRole(SwiftDropRoles.ROLE_ADMIN);
    }

    public boolean isCourier() {
        return hasRole(SwiftDropRoles.ROLE_COURIER);
    }

    public boolean isDispatcher() {
        return hasRole(SwiftDropRoles.ROLE_DISPATCHER);
    }

    public boolean isMerchant() {
        return hasRole(SwiftDropRoles.ROLE_MERCHANT);
    }

    public boolean isCustomer() {
        return hasRole(SwiftDropRoles.ROLE_CUSTOMER);
    }
}
