package com.swiftdrop.accountsservice.grpc;

import com.swiftdrop.security.grpc.ActorDelegationValidator;
import com.swiftdrop.security.grpc.GrpcSecurityPolicy;
import com.swiftdrop.security.grpc.SwiftDropGrpcServerSecurityInterceptor;
import com.swiftdrop.security.service.ServiceTokenValidator;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Manages the lifecycle of the Accounts gRPC server.
 * Binds SwiftDropGrpcServerSecurityInterceptor for M2M cryptographic authentication,
 * caller authorization, and actor delegation.
 */
@Component
public class AccountsGrpcServerLifecycle implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(AccountsGrpcServerLifecycle.class);

    private final AccountsInternalServiceImpl accountsInternalService;
    private final ServiceTokenValidator serviceTokenValidator;
    private final ActorDelegationValidator actorDelegationValidator;
    private final GrpcSecurityPolicy grpcSecurityPolicy;
    private final int port;
    private final boolean enabled;

    private Server server;
    private volatile boolean running = false;

    public AccountsGrpcServerLifecycle(
            AccountsInternalServiceImpl accountsInternalService,
            ServiceTokenValidator serviceTokenValidator,
            ActorDelegationValidator actorDelegationValidator,
            GrpcSecurityPolicy grpcSecurityPolicy,
            @Value("${grpc.server.port:9081}") int port,
            @Value("${grpc.server.enabled:true}") boolean enabled) {
        this.accountsInternalService = accountsInternalService;
        this.serviceTokenValidator = serviceTokenValidator;
        this.actorDelegationValidator = actorDelegationValidator;
        this.grpcSecurityPolicy = grpcSecurityPolicy;
        this.port = port;
        this.enabled = enabled;
    }

    @Override
    public void start() {
        if (!enabled) {
            log.info("Accounts gRPC server is disabled via configuration");
            return;
        }

        try {
            SwiftDropGrpcServerSecurityInterceptor securityInterceptor =
                    new SwiftDropGrpcServerSecurityInterceptor(serviceTokenValidator, actorDelegationValidator, grpcSecurityPolicy);

            server = ServerBuilder.forPort(port)
                    .intercept(securityInterceptor)
                    .addService(accountsInternalService)
                    .build()
                    .start();

            running = true;
            log.info("Accounts gRPC server started on port {}", server.getPort());

        } catch (IOException ex) {
            log.error("Failed to start Accounts gRPC server on port {}", port, ex);
            throw new IllegalStateException("Could not start Accounts gRPC server", ex);
        }
    }

    @Override
    public void stop() {
        if (server != null) {
            log.info("Shutting down Accounts gRPC server");
            server.shutdown();
            try {
                if (!server.awaitTermination(5, TimeUnit.SECONDS)) {
                    server.shutdownNow();
                }
            } catch (InterruptedException ex) {
                server.shutdownNow();
                Thread.currentThread().interrupt();
            } finally {
                running = false;
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running && server != null && !server.isShutdown();
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100;
    }

    @Override
    public boolean isAutoStartup() {
        return enabled;
    }

    public int getPort() {
        return server != null ? server.getPort() : port;
    }

    public Server getServer() {
        return server;
    }
}
