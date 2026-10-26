package com.swiftdrop.graphql.client;

import com.swiftdrop.accounts.v1.AccountsInternalServiceGrpc;
import com.swiftdrop.delivery.v1.DeliveryInternalServiceGrpc;
import com.swiftdrop.security.grpc.SwiftDropGrpcClientInterceptor;
import com.swiftdrop.security.grpc.SwiftDropGrpcContext;
import com.swiftdrop.security.service.ServiceTokenProvider;
import graphql.GraphQLContext;
import io.grpc.Context;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** Created by resolver-owner configuration using Platform's rotating token provider. No static token fallback. */
public final class AuthenticatedReadClients implements AutoCloseable {
    private final ManagedChannel channel;
    private final SwiftDropGrpcClientInterceptor interceptor;

    public AuthenticatedReadClients(String target, ServiceTokenProvider tokens) {
        this(ManagedChannelBuilder.forTarget(target).useTransportSecurity().maxInboundMessageSize(262144).build(), tokens);
    }
    // Package-private test seam; deployed construction always requires TLS.
    AuthenticatedReadClients(ManagedChannel channel, ServiceTokenProvider tokens) {
        this.channel = channel;
        this.interceptor = new SwiftDropGrpcClientInterceptor(() -> {
            String token = tokens.getServiceToken();
            if (token == null || token.isBlank()) throw Status.UNAUTHENTICATED.asRuntimeException();
            return token;
        }, 2000);
    }

    public <T> T accounts(GraphQLContext context, Function<AccountsInternalServiceGrpc.AccountsInternalServiceBlockingStub, T> read) {
        return scoped(context, () -> read.apply(AccountsInternalServiceGrpc.newBlockingStub(channel)
                .withInterceptors(interceptor).withMaxInboundMessageSize(262144)));
    }
    public <T> T delivery(GraphQLContext context, Function<DeliveryInternalServiceGrpc.DeliveryInternalServiceBlockingStub, T> read) {
        return scoped(context, () -> read.apply(DeliveryInternalServiceGrpc.newBlockingStub(channel)
                .withInterceptors(interceptor).withMaxInboundMessageSize(262144)));
    }
    private <T> T scoped(GraphQLContext context, java.util.concurrent.Callable<T> read) {
        JwtAuthenticationToken actor = context.get("actor");
        if (actor == null || !actor.isAuthenticated()) throw Status.UNAUTHENTICATED.asRuntimeException();
        SecurityContext previous = SecurityContextHolder.getContext();
        SecurityContext current = SecurityContextHolder.createEmptyContext();
        current.setAuthentication(actor);
        // Clear any inherited unproved delegation; the shared interceptor forwards the signed actor JWT instead.
        Context grpc = Context.current().withValue(SwiftDropGrpcContext.KEY_DELEGATED_ACTOR, null)
                .withValue(SwiftDropGrpcContext.KEY_CORRELATION_ID, context.get("correlationId"));
        try {
            SecurityContextHolder.setContext(current);
            return grpc.call(read);
        } catch (RuntimeException e) { throw e; }
        catch (Exception e) { throw Status.INTERNAL.asRuntimeException(); }
        finally { SecurityContextHolder.setContext(previous); }
    }
    @Override public void close() {
        channel.shutdownNow();
        try { channel.awaitTermination(5, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
