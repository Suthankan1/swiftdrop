package com.swiftdrop.communicationsservice.client;

import com.swiftdrop.security.grpc.SwiftDropGrpcClientInterceptor;
import com.swiftdrop.security.service.ServiceTokenProvider;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Optional;

/**
 * Spring configuration providing gRPC channel and client adapter for Accounts Service.
 */
@Configuration
public class AccountsGrpcClientConfig {

    @Value("${grpc.client.accounts.host:localhost}")
    private String host;

    @Value("${grpc.client.accounts.port:9081}")
    private int port;

    @Value("${grpc.client.accounts.deadline-ms:2000}")
    private long deadlineMs;

    @Bean
    @ConditionalOnMissingBean
    public ServiceTokenProvider communicationsServiceTokenProvider(
            @Value("${grpc.client.accounts.service-token:}") String configuredToken) {
        if (configuredToken != null && !configuredToken.isBlank()) {
            return ServiceTokenProvider.of(configuredToken.trim());
        }
        String envToken = System.getenv("COMMUNICATIONS_SERVICE_JWT");
        if (envToken != null && !envToken.isBlank()) {
            return ServiceTokenProvider.of(envToken.trim());
        }
        // Fallback placeholder token for test/local development
        return () -> "";
    }

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean(name = "accountsGrpcManagedChannel")
    public ManagedChannel accountsGrpcManagedChannel(ServiceTokenProvider tokenProvider) {
        SwiftDropGrpcClientInterceptor interceptor =
                new SwiftDropGrpcClientInterceptor(tokenProvider, deadlineMs);

        return ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .intercept(interceptor)
                .build();
    }

    @Bean
    @ConditionalOnMissingBean(AccountsRecipientContactClient.class)
    public AccountsRecipientContactClient accountsRecipientContactClient(
            ManagedChannel accountsGrpcManagedChannel) {
        return new GrpcAccountsRecipientContactClient(accountsGrpcManagedChannel, deadlineMs);
    }
}
