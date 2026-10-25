package com.swiftdrop.accountsservice.grpc;

import com.swiftdrop.security.grpc.ActorDelegationValidator;
import com.swiftdrop.security.grpc.GrpcSecurityPolicy;
import com.swiftdrop.security.service.ServiceTokenValidator;
import io.grpc.BindableService;
import io.grpc.ServerServiceDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AccountsGrpcServerLifecycleTest {

    private int findRandomOpenPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @Test
    @DisplayName("AccountsGrpcServerLifecycle starts and stops cleanly on configured port")
    void testServerLifecycleStartAndStop() throws Exception {
        int port = findRandomOpenPort();

        AccountsInternalServiceImpl mockService = mock(AccountsInternalServiceImpl.class);
        when(mockService.bindService()).thenReturn(ServerServiceDefinition.builder("mock.Service").build());

        ServiceTokenValidator mockServiceValidator = mock(ServiceTokenValidator.class);
        ActorDelegationValidator mockActorValidator = mock(ActorDelegationValidator.class);
        GrpcSecurityPolicy securityPolicy = new GrpcSecurityPolicy();

        AccountsGrpcServerLifecycle lifecycle = new AccountsGrpcServerLifecycle(
                mockService,
                mockServiceValidator,
                mockActorValidator,
                securityPolicy,
                port,
                true
        );

        assertThat(lifecycle.isRunning()).isFalse();
        lifecycle.start();
        assertThat(lifecycle.isRunning()).isTrue();
        assertThat(lifecycle.getPort()).isEqualTo(port);

        lifecycle.stop();
        assertThat(lifecycle.isRunning()).isFalse();
    }

    @Test
    @DisplayName("AccountsGrpcServerLifecycle does not start when enabled is false")
    void testServerLifecycleDisabled() throws Exception {
        AccountsInternalServiceImpl mockService = mock(AccountsInternalServiceImpl.class);
        ServiceTokenValidator mockServiceValidator = mock(ServiceTokenValidator.class);
        ActorDelegationValidator mockActorValidator = mock(ActorDelegationValidator.class);
        GrpcSecurityPolicy securityPolicy = new GrpcSecurityPolicy();

        AccountsGrpcServerLifecycle lifecycle = new AccountsGrpcServerLifecycle(
                mockService,
                mockServiceValidator,
                mockActorValidator,
                securityPolicy,
                9081,
                false
        );

        lifecycle.start();
        assertThat(lifecycle.isRunning()).isFalse();
        lifecycle.stop();
        assertThat(lifecycle.isRunning()).isFalse();
    }
}
