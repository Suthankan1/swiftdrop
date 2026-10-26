package com.swiftdrop.pricing.grpc;

import com.swiftdrop.security.grpc.ActorDelegationValidator;
import com.swiftdrop.security.grpc.GrpcSecurityPolicy;
import com.swiftdrop.security.service.JwtServiceTokenValidator;
import com.swiftdrop.security.service.ServiceTokenValidator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * Spring configuration providing gRPC security validators and policy beans for Pricing Service.
 */
@Configuration(proxyBeanMethods = false)
public class PricingGrpcServerConfig {

    @Bean
    @ConditionalOnMissingBean
    public ServiceTokenValidator grpcServiceTokenValidator(
            JwtDecoder jwtDecoder,
            @Value("${JWT_SERVICE_AUDIENCE:swiftdrop-internal}") String serviceAudience) {
        return new JwtServiceTokenValidator(jwtDecoder, serviceAudience, null);
    }

    @Bean
    @ConditionalOnMissingBean
    public ActorDelegationValidator grpcActorDelegationValidator(JwtDecoder jwtDecoder) {
        return new ActorDelegationValidator(jwtDecoder);
    }

    @Bean
    @ConditionalOnMissingBean
    public GrpcSecurityPolicy grpcSecurityPolicy() {
        return new GrpcSecurityPolicy();
    }
}
