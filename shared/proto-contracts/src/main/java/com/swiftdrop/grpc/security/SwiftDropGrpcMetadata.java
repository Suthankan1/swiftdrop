package com.swiftdrop.grpc.security;

import io.grpc.Metadata;

/**
 * Standard security, actor delegation, and tracing metadata keys for SwiftDrop internal gRPC interfaces.
 * Conforms to Architecture Baseline v1.3 and contracts/grpc/README.md Section 5.
 */
public final class SwiftDropGrpcMetadata {

    private SwiftDropGrpcMetadata() {
        // Utility class
    }

    /**
     * Header key carrying the signed JWT token for service-to-service authentication.
     */
    public static final String SERVICE_TOKEN_HEADER_KEY = "x-swiftdrop-service-token";

    /**
     * Header key carrying the initiating Keycloak user subject (UUID string) for delegated actions.
     */
    public static final String ACTOR_SUB_HEADER_KEY = "x-swiftdrop-actor-sub";

    /**
     * Header key carrying the comma-separated user roles (e.g. "ADMIN,DISPATCHER") of the initiating actor.
     */
    public static final String ACTOR_ROLES_HEADER_KEY = "x-swiftdrop-actor-roles";

    /**
     * Header key carrying the end-to-end distributed tracing correlation ID.
     */
    public static final String CORRELATION_ID_HEADER_KEY = "x-swiftdrop-correlation-id";

    /**
     * Default client RPC deadline in milliseconds (2000ms / 2s bounded timeout).
     */
    public static final long DEFAULT_DEADLINE_MS = 2000L;

    public static final Metadata.Key<String> KEY_SERVICE_TOKEN =
            Metadata.Key.of(SERVICE_TOKEN_HEADER_KEY, Metadata.ASCII_STRING_MARSHALLER);

    public static final Metadata.Key<String> KEY_ACTOR_SUB =
            Metadata.Key.of(ACTOR_SUB_HEADER_KEY, Metadata.ASCII_STRING_MARSHALLER);

    public static final Metadata.Key<String> KEY_ACTOR_ROLES =
            Metadata.Key.of(ACTOR_ROLES_HEADER_KEY, Metadata.ASCII_STRING_MARSHALLER);

    public static final Metadata.Key<String> KEY_CORRELATION_ID =
            Metadata.Key.of(CORRELATION_ID_HEADER_KEY, Metadata.ASCII_STRING_MARSHALLER);
}
