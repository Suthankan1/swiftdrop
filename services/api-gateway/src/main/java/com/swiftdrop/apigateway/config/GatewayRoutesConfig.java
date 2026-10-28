package com.swiftdrop.apigateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Programmatic Spring Cloud Gateway route definitions for approved SwiftDrop business endpoints.
 * Wires approved routes to downstream microservices in accordance with the frozen interface matrix:
 *   - Accounts & Partners: /api/v1/users/me, /api/v1/merchants/**, /api/v1/admin/**
 *   - Pricing & Zones: /api/v1/quotes/**, /api/v1/zones/**, /api/v1/admin/pricing/**
 *   - Delivery Operations: /api/v1/tracking/**, /api/v1/merchant/parcels/**, /api/v1/assignments/**, /api/v1/parcels/**
 *   - Communications: /api/v1/merchants/webhooks/**
 *   - GraphQL Query API: /graphql
 */
@Configuration
public class GatewayRoutesConfig {

    private static final int ROUTE_ORDER = 10;
    private static final int SPECIFIC_ROUTE_ORDER = 5;

    @Bean
    public RouteLocator routeLocator(
            RouteLocatorBuilder builder,
            @Value("${ACCOUNTS_SERVICE_URL:http://localhost:8081}") String accountsServiceUrl,
            @Value("${PRICING_SERVICE_URL:http://localhost:8082}") String pricingServiceUrl,
            @Value("${DELIVERY_SERVICE_URL:http://localhost:8083}") String deliveryServiceUrl,
            @Value("${COMMUNICATIONS_SERVICE_URL:http://localhost:8084}") String communicationsServiceUrl,
            @Value("${GRAPHQL_SERVICE_URL:http://localhost:8084}") String graphqlServiceUrl) {

        return builder.routes()
                // Specific webhook route to Communications service (evaluates before generic /api/v1/merchants/**)
                .route("communications-service-webhooks", r -> r
                        .order(SPECIFIC_ROUTE_ORDER)
                        .path("/api/v1/merchants/webhooks", "/api/v1/merchants/webhooks/**")
                        .uri(communicationsServiceUrl))

                // Accounts & Partners service routes
                .route("accounts-service-users-me", r -> r
                        .order(ROUTE_ORDER)
                        .path("/api/v1/users/me")
                        .uri(accountsServiceUrl))
                .route("accounts-service-merchants", r -> r
                        .order(ROUTE_ORDER)
                        .path("/api/v1/merchants", "/api/v1/merchants/**")
                        .uri(accountsServiceUrl))
                .route("accounts-service-admin", r -> r
                        .order(ROUTE_ORDER)
                        .path("/api/v1/admin/**")
                        .uri(accountsServiceUrl))

                // Pricing & Zones service routes
                // More specific than Accounts' generic admin route; ADMIN policy is unchanged.
                .route("pricing-service-admin", r -> r
                        .order(SPECIFIC_ROUTE_ORDER)
                        .path("/api/v1/admin/pricing", "/api/v1/admin/pricing/**")
                        .uri(pricingServiceUrl))
                .route("pricing-service-quotes", r -> r
                        .order(ROUTE_ORDER)
                        .path("/api/v1/quotes", "/api/v1/quotes/**")
                        .uri(pricingServiceUrl))
                .route("pricing-service-zones", r -> r
                        .order(ROUTE_ORDER)
                        .path("/api/v1/zones", "/api/v1/zones/**")
                        .uri(pricingServiceUrl))

                // Delivery Operations service routes
                .route("delivery-service-tracking", r -> r
                        .order(ROUTE_ORDER)
                        .path("/api/v1/tracking/**")
                        .metadata("response-timeout", -1)
                        .uri(deliveryServiceUrl))
                .route("delivery-service-merchant-parcels", r -> r
                        .order(ROUTE_ORDER)
                        .path("/api/v1/merchant/parcels", "/api/v1/merchant/parcels/**")
                        .uri(deliveryServiceUrl))
                .route("delivery-service-assignments", r -> r
                        .order(ROUTE_ORDER)
                        .path("/api/v1/assignments", "/api/v1/assignments/**")
                        .uri(deliveryServiceUrl))
                .route("delivery-service-parcels", r -> r
                        .order(ROUTE_ORDER)
                        .path("/api/v1/parcels", "/api/v1/parcels/**")
                        .metadata("response-timeout", -1)
                        .uri(deliveryServiceUrl))

                // Read-only composite GraphQL Query API
                .route("graphql-query-service", r -> r
                        .order(ROUTE_ORDER)
                        .path("/graphql")
                        .uri(graphqlServiceUrl))
                .build();
    }
}
