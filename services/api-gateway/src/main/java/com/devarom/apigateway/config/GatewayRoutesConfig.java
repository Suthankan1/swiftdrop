package com.devarom.apigateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Programmatic Spring Cloud Gateway route definition for approved Devarom endpoints.
 * Routes GET /api/v1/users/me to the Accounts & Partners Service without path modification.
 */
@Configuration
public class GatewayRoutesConfig {

    @Bean
    public RouteLocator routeLocator(RouteLocatorBuilder builder,
                                     @Value("${ACCOUNTS_SERVICE_URL:http://localhost:8081}") String accountsServiceUrl) {
        return builder.routes()
                .route("accounts-service-users-me", r -> r
                        .path("/api/v1/users/me")
                        .uri(accountsServiceUrl))
                .build();
    }
}
