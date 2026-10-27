package com.swiftdrop.deliveryservice.grpc;

import com.swiftdrop.security.grpc.ActorDelegationValidator;
import com.swiftdrop.security.grpc.GrpcSecurityPolicy;
import com.swiftdrop.security.service.ServiceTokenValidator;
import io.grpc.ServerServiceDefinition;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.io.IOException;
import java.net.ServerSocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DeliveryGrpcServerLifecycleTest {

    private int findRandomOpenPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @Test
    @DisplayName("DeliveryGrpcServerLifecycle constructs via ObjectProvider with MeterRegistry present")
    void testConstructWithMeterRegistryPresent() throws Exception {
        int port = findRandomOpenPort();
        DeliveryInternalServiceImpl mockService = mock(DeliveryInternalServiceImpl.class);
        when(mockService.bindService()).thenReturn(ServerServiceDefinition.builder("mock.DeliveryService").build());

        ServiceTokenValidator mockServiceValidator = mock(ServiceTokenValidator.class);
        ActorDelegationValidator mockActorValidator = mock(ActorDelegationValidator.class);
        GrpcSecurityPolicy securityPolicy = new GrpcSecurityPolicy();
        MeterRegistry registry = new SimpleMeterRegistry();

        @SuppressWarnings("unchecked")
        ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(registry);

        DeliveryGrpcServerLifecycle lifecycle = new DeliveryGrpcServerLifecycle(
                mockService,
                mockServiceValidator,
                mockActorValidator,
                securityPolicy,
                port,
                true,
                provider
        );

        assertThat(lifecycle.isRunning()).isFalse();
        lifecycle.start();
        assertThat(lifecycle.isRunning()).isTrue();
        assertThat(lifecycle.getPort()).isEqualTo(port);

        lifecycle.stop();
        assertThat(lifecycle.isRunning()).isFalse();
    }

    @Test
    @DisplayName("DeliveryGrpcServerLifecycle constructs via ObjectProvider when MeterRegistry is absent")
    void testConstructWithMeterRegistryAbsent() throws Exception {
        int port = findRandomOpenPort();
        DeliveryInternalServiceImpl mockService = mock(DeliveryInternalServiceImpl.class);
        when(mockService.bindService()).thenReturn(ServerServiceDefinition.builder("mock.DeliveryService").build());

        ServiceTokenValidator mockServiceValidator = mock(ServiceTokenValidator.class);
        ActorDelegationValidator mockActorValidator = mock(ActorDelegationValidator.class);
        GrpcSecurityPolicy securityPolicy = new GrpcSecurityPolicy();

        @SuppressWarnings("unchecked")
        ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);

        DeliveryGrpcServerLifecycle lifecycle = new DeliveryGrpcServerLifecycle(
                mockService,
                mockServiceValidator,
                mockActorValidator,
                securityPolicy,
                port,
                true,
                provider
        );

        assertThat(lifecycle.isRunning()).isFalse();
        lifecycle.start();
        assertThat(lifecycle.isRunning()).isTrue();
        lifecycle.stop();
        assertThat(lifecycle.isRunning()).isFalse();
    }

    @Test
    @DisplayName("DeliveryGrpcServerLifecycle compatibility constructor functions without metrics")
    void testCompatibilityConstructor() {
        DeliveryInternalServiceImpl mockService = mock(DeliveryInternalServiceImpl.class);
        ServiceTokenValidator mockServiceValidator = mock(ServiceTokenValidator.class);
        ActorDelegationValidator mockActorValidator = mock(ActorDelegationValidator.class);
        GrpcSecurityPolicy securityPolicy = new GrpcSecurityPolicy();

        DeliveryGrpcServerLifecycle lifecycle = new DeliveryGrpcServerLifecycle(
                mockService,
                mockServiceValidator,
                mockActorValidator,
                securityPolicy,
                9082,
                false
        );

        lifecycle.start();
        assertThat(lifecycle.isRunning()).isFalse();
        lifecycle.stop();
    }
}
