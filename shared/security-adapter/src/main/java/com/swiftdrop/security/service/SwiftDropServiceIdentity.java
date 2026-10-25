package com.swiftdrop.security.service;

import java.util.Arrays;
import java.util.Optional;

/**
 * Authoritative enum of registered internal SwiftDrop services for machine-to-machine trust boundaries.
 */
public enum SwiftDropServiceIdentity {

    ACCOUNTS_SERVICE("accounts-service"),
    PRICING_SERVICE("pricing-service"),
    DELIVERY_SERVICE("delivery-service"),
    COMMUNICATIONS_SERVICE("communications-service"),
    GRAPHQL_QUERY_SERVICE("graphql-query-service"),
    API_GATEWAY("api-gateway");

    private final String serviceName;

    SwiftDropServiceIdentity(String serviceName) {
        this.serviceName = serviceName;
    }

    public String getServiceName() {
        return serviceName;
    }

    public static Optional<SwiftDropServiceIdentity> fromServiceName(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String clean = name.trim().toLowerCase();
        return Arrays.stream(values())
                .filter(s -> s.serviceName.equalsIgnoreCase(clean)
                        || s.name().equalsIgnoreCase(clean.replace('-', '_')))
                .findFirst();
    }
}
