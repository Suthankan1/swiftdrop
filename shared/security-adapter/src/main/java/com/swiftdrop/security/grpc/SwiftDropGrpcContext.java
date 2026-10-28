package com.swiftdrop.security.grpc;

import com.swiftdrop.security.service.SwiftDropServiceIdentity;
import io.grpc.Context;

import java.util.Optional;

/**
 * Request-scoped gRPC Context holder for validated calling service, delegated actor, and correlation ID.
 * Prevents static state leaks across concurrent threads.
 */
public final class SwiftDropGrpcContext {

    public static final Context.Key<SwiftDropServiceIdentity> KEY_CALLING_SERVICE =
            Context.key("swiftdrop-calling-service");

    public static final Context.Key<DelegatedActorContext> KEY_DELEGATED_ACTOR =
            Context.key("swiftdrop-delegated-actor");

    public static final Context.Key<String> KEY_CORRELATION_ID =
            Context.key("swiftdrop-correlation-id");

    private SwiftDropGrpcContext() {}

    public static Optional<SwiftDropServiceIdentity> currentCallingService() {
        return Optional.ofNullable(KEY_CALLING_SERVICE.get());
    }

    public static Optional<DelegatedActorContext> currentDelegatedActor() {
        return Optional.ofNullable(KEY_DELEGATED_ACTOR.get());
    }

    public static Optional<String> currentCorrelationId() {
        return Optional.ofNullable(KEY_CORRELATION_ID.get());
    }
}
