package com.swiftdrop.graphql.config;

import com.swiftdrop.graphql.client.AuthenticatedReadClients;
import com.swiftdrop.security.service.ServiceTokenProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class GrpcClientConfig {
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "swiftdrop.grpc.accounts-target")
    AuthenticatedReadClients accountsReadClients(@Value("${swiftdrop.grpc.accounts-target}") String target,
                                                ServiceTokenProvider tokens) {
        return new AuthenticatedReadClients(target, tokens);
    }
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "swiftdrop.grpc.delivery-target")
    AuthenticatedReadClients deliveryReadClients(@Value("${swiftdrop.grpc.delivery-target}") String target,
                                                ServiceTokenProvider tokens) {
        return new AuthenticatedReadClients(target, tokens);
    }
}
