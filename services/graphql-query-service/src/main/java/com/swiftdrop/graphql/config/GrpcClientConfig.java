package com.swiftdrop.graphql.config;

import com.swiftdrop.graphql.client.AuthenticatedReadClients;
import com.swiftdrop.security.service.ClientCredentialsServiceTokenProvider;
import com.swiftdrop.security.service.ServiceTokenProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class GrpcClientConfig {
    @Bean
    @ConditionalOnMissingBean
    ServiceTokenProvider graphqlServiceTokenProvider(
            @Value("${KEYCLOAK_JWKS_URI:}") String jwksUri,
            @Value("${SWIFTDROP_GRAPHQL_CLIENT_ID:graphql-query-service}") String clientId,
            @Value("${SWIFTDROP_GRAPHQL_CLIENT_SECRET:}") String clientSecret) {
        if (clientSecret.isBlank() || jwksUri.isBlank()) {
            // Fails closed: AuthenticatedReadClients rejects a blank token before any remote call.
            return () -> "";
        }
        return new ClientCredentialsServiceTokenProvider(
                jwksUri.replace("/protocol/openid-connect/certs", "/protocol/openid-connect/token"),
                clientId, clientSecret.trim());
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "swiftdrop.grpc.accounts-target")
    AuthenticatedReadClients accountsReadClients(@Value("${swiftdrop.grpc.accounts-target}") String target,
                                                @Value("${swiftdrop.grpc.plaintext:false}") boolean plaintext,
                                                ServiceTokenProvider tokens) {
        return AuthenticatedReadClients.forTarget(target, tokens, plaintext);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "swiftdrop.grpc.delivery-target")
    AuthenticatedReadClients deliveryReadClients(@Value("${swiftdrop.grpc.delivery-target}") String target,
                                                @Value("${swiftdrop.grpc.plaintext:false}") boolean plaintext,
                                                ServiceTokenProvider tokens) {
        return AuthenticatedReadClients.forTarget(target, tokens, plaintext);
    }
}
