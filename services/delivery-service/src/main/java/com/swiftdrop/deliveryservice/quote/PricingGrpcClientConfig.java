package com.swiftdrop.deliveryservice.quote;

import com.swiftdrop.pricing.v1.PricingInternalServiceGrpc;
import com.swiftdrop.security.grpc.SwiftDropGrpcClientInterceptor;
import com.swiftdrop.security.service.ServiceTokenProvider;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration creating the gRPC channel, blocking stub, and BookingQuoteProvider adapter
 * connecting Delivery Operations to Pricing & Zones Service (IF-GRPC-04).
 */
@Configuration(proxyBeanMethods = false)
public class PricingGrpcClientConfig {

    @Value("${PRICING_GRPC_HOST:${swiftdrop.pricing.grpc.host:localhost}}")
    private String pricingHost;

    @Value("${PRICING_GRPC_PORT:${swiftdrop.pricing.grpc.port:9083}}")
    private int pricingPort;

    @Value("${PRICING_GRPC_DEADLINE_MS:${swiftdrop.pricing.grpc.deadline-ms:5000}}")
    private long deadlineMs;

    @Bean
    @ConditionalOnMissingBean(ServiceTokenProvider.class)
    public ServiceTokenProvider serviceTokenProvider(
            @Value("${SWIFTDROP_DELIVERY_SERVICE_JWT:${swiftdrop.delivery.service-token:}}") String staticToken,
            @Value("${KEYCLOAK_TOKEN_URI:${swiftdrop.keycloak.token-uri:}}") String tokenUri,
            @Value("${KEYCLOAK_JWKS_URI:${swiftdrop.keycloak.jwks-uri:${spring.security.oauth2.resourceserver.jwt.jwk-set-uri:}}}") String jwksUri,
            @Value("${KEYCLOAK_ISSUER_URI:${swiftdrop.keycloak.issuer-uri:${spring.security.oauth2.resourceserver.jwt.issuer-uri:http://localhost:8090/realms/swiftdrop}}}") String issuerUri,
            @Value("${SWIFTDROP_DELIVERY_CLIENT_ID:delivery-service}") String clientId,
            @Value("${SWIFTDROP_DELIVERY_CLIENT_SECRET:delivery-service-secret-internal}") String clientSecret) {
        return new KeycloakServiceTokenProvider(staticToken, tokenUri, jwksUri, issuerUri, clientId, clientSecret);
    }

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean(name = "pricingGrpcManagedChannel")
    public ManagedChannel pricingGrpcManagedChannel(ServiceTokenProvider serviceTokenProvider) {
        SwiftDropGrpcClientInterceptor interceptor =
                new SwiftDropGrpcClientInterceptor(serviceTokenProvider, deadlineMs);

        return ManagedChannelBuilder.forAddress(pricingHost, pricingPort)
                .usePlaintext()
                .intercept(interceptor)
                .build();
    }

    @Bean
    @ConditionalOnMissingBean
    public PricingInternalServiceGrpc.PricingInternalServiceBlockingStub pricingStub(ManagedChannel pricingGrpcManagedChannel) {
        return PricingInternalServiceGrpc.newBlockingStub(pricingGrpcManagedChannel);
    }

    @Bean
    @ConditionalOnMissingBean(BookingQuoteProvider.class)
    public BookingQuoteProvider bookingQuoteProvider(PricingInternalServiceGrpc.PricingInternalServiceBlockingStub pricingStub) {
        return new GrpcBookingQuoteProvider(pricingStub, deadlineMs);
    }
}

