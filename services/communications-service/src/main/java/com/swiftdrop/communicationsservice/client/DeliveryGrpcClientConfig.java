package com.swiftdrop.communicationsservice.client;

import com.swiftdrop.security.grpc.SwiftDropGrpcClientInterceptor;
import com.swiftdrop.security.service.ServiceTokenProvider;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration providing gRPC channel and client adapter for Delivery Operations Service.
 */
@Configuration
public class DeliveryGrpcClientConfig {

    @Value("${grpc.client.delivery.host:localhost}")
    private String host;

    @Value("${grpc.client.delivery.port:9082}")
    private int port;

    @Value("${grpc.client.delivery.deadline-ms:2000}")
    private long deadlineMs;

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean(name = "deliveryGrpcManagedChannel")
    public ManagedChannel deliveryGrpcManagedChannel(ServiceTokenProvider tokenProvider) {
        SwiftDropGrpcClientInterceptor interceptor =
                new SwiftDropGrpcClientInterceptor(tokenProvider, deadlineMs);

        return ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .intercept(interceptor)
                .build();
    }

    @Bean
    @ConditionalOnMissingBean(DeliveryRecipientAuthorizationClient.class)
    public DeliveryRecipientAuthorizationClient deliveryRecipientAuthorizationClient(
            ManagedChannel deliveryGrpcManagedChannel) {
        return new GrpcDeliveryRecipientAuthorizationClient(deliveryGrpcManagedChannel, deadlineMs);
    }
}
