package com.swiftdrop.deliveryservice.grpc;

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
 * Manages the lifecycle of the Delivery gRPC server.
 * Binds SwiftDropGrpcServerSecurityInterceptor for M2M cryptographic authentication,
 * caller authorization, and actor delegation.
 */
@Component
public class DeliveryGrpcServerLifecycle implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(DeliveryGrpcServerLifecycle.class);

    private final DeliveryInternalServiceImpl deliveryInternalService;
    private final ServiceTokenValidator serviceTokenValidator;
    private final ActorDelegationValidator actorDelegationValidator;
    private final GrpcSecurityPolicy grpcSecurityPolicy;
    private final int port;
    private final boolean enabled;

    private Server server;
    private volatile boolean running = false;

    public DeliveryGrpcServerLifecycle(
            DeliveryInternalServiceImpl deliveryInternalService,
            ServiceTokenValidator serviceTokenValidator,
            ActorDelegationValidator actorDelegationValidator,
            GrpcSecurityPolicy grpcSecurityPolicy,
            @Value("${grpc.server.port:9083}") int port,
            @Value("${grpc.server.enabled:true}") boolean enabled) {
        this.deliveryInternalService = deliveryInternalService;
        this.serviceTokenValidator = serviceTokenValidator;
        this.actorDelegationValidator = actorDelegationValidator;
        this.grpcSecurityPolicy = grpcSecurityPolicy;
        this.port = port;
        this.enabled = enabled;
    }

    @Override
    public void start() {
        if (!enabled) {
            log.info("Delivery gRPC server is disabled via configuration");
            return;
        }

        try {
            SwiftDropGrpcServerSecurityInterceptor securityInterceptor =
                    new SwiftDropGrpcServerSecurityInterceptor(serviceTokenValidator, actorDelegationValidator, grpcSecurityPolicy);

            server = ServerBuilder.forPort(port)
                    .intercept(securityInterceptor)
                    .addService(deliveryInternalService)
                    .build()
                    .start();

            running = true;
            log.info("Delivery gRPC server started on port {}", server.getPort());

        } catch (IOException ex) {
            log.error("Failed to start Delivery gRPC server on port {}", port, ex);
            throw new IllegalStateException("Could not start Delivery gRPC server", ex);
        }
    }

    @Override
    public void stop() {
        if (server != null) {
            log.info("Shutting down Delivery gRPC server");
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
