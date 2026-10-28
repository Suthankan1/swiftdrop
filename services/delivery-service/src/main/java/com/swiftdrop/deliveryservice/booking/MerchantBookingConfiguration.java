package com.swiftdrop.deliveryservice.booking;

import com.swiftdrop.accounts.v1.AccountsInternalServiceGrpc;
import com.swiftdrop.deliveryservice.quote.BookingAuthorization;
import com.swiftdrop.deliveryservice.quote.BookingQuoteProvider;
import com.swiftdrop.deliveryservice.quote.GrpcBookingQuoteProvider;
import com.swiftdrop.deliveryservice.security.merchant.AccountsMerchantKeyValidator;
import com.swiftdrop.deliveryservice.security.merchant.GrpcAccountsMerchantKeyValidator;
import com.swiftdrop.pricing.v1.PricingInternalServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/**
 * Spring configuration providing default beans and gRPC provider connections for Delivery booking intake.
 */
@Configuration
public class MerchantBookingConfiguration {

    @Bean
    @ConditionalOnMissingBean(JsonMapper.class)
    public JsonMapper jsonMapper() {
        return JsonMapper.builder().build();
    }

    @Bean
    @ConditionalOnMissingBean(BookingAuthorization.class)
    public BookingAuthorization bookingAuthorization() {
        return (subject, request) -> {
            // Default foundation authorization seam
        };
    }

    @Bean
    @ConditionalOnMissingBean(AccountsMerchantKeyValidator.class)
    public AccountsMerchantKeyValidator accountsMerchantKeyValidator(
            @Value("${ACCOUNTS_GRPC_HOST:localhost}") String host,
            @Value("${ACCOUNTS_GRPC_PORT:9081}") int port) {
        ManagedChannel channel = ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .build();
        AccountsInternalServiceGrpc.AccountsInternalServiceBlockingStub stub =
                AccountsInternalServiceGrpc.newBlockingStub(channel);
        return new GrpcAccountsMerchantKeyValidator(stub);
    }

    @Bean
    @ConditionalOnMissingBean(BookingQuoteProvider.class)
    public BookingQuoteProvider bookingQuoteProvider(
            @Value("${PRICING_GRPC_HOST:localhost}") String host,
            @Value("${PRICING_GRPC_PORT:9082}") int port) {
        ManagedChannel channel = ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .build();
        PricingInternalServiceGrpc.PricingInternalServiceBlockingStub stub =
                PricingInternalServiceGrpc.newBlockingStub(channel);
        return new GrpcBookingQuoteProvider(stub);
    }
}
