package com.swiftdrop.apigateway.security;

import com.swiftdrop.apigateway.security.merchant.MerchantApiKeyAuthenticationFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Reactive Spring Security configuration for SwiftDrop Spring Cloud API Gateway.
 * Configures OAuth2 Resource Server for Keycloak Bearer JWT verification,
 * Merchant machine API-key authentication filter, RFC 7807 problem detail handlers,
 * and role-based policy gates across approved route families.
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    private final ProblemDetailAuthenticationEntryPoint authenticationEntryPoint;
    private final ProblemDetailAccessDeniedHandler accessDeniedHandler;
    private final MerchantApiKeyAuthenticationFilter merchantApiKeyAuthenticationFilter;

    public SecurityConfig(
            ProblemDetailAuthenticationEntryPoint authenticationEntryPoint,
            ProblemDetailAccessDeniedHandler accessDeniedHandler,
            MerchantApiKeyAuthenticationFilter merchantApiKeyAuthenticationFilter) {
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.accessDeniedHandler = accessDeniedHandler;
        this.merchantApiKeyAuthenticationFilter = merchantApiKeyAuthenticationFilter;
    }

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http, ReactiveJwtDecoder jwtDecoder) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .cors(Customizer.withDefaults())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                )
                .addFilterAt(merchantApiKeyAuthenticationFilter, SecurityWebFiltersOrder.AUTHENTICATION)
                .authorizeExchange(exchanges -> exchanges
                        // Public actuator health probes
                        .pathMatchers("/actuator/health", "/actuator/health/**").permitAll()

                        // CORS preflight options requests
                        .pathMatchers(HttpMethod.OPTIONS).permitAll()

                        // Public parcel tracking (REST read and SSE public tracking stream)
                        .pathMatchers(HttpMethod.GET, "/api/v1/tracking/**").permitAll()

                        // Public pricing quote calculation and reads
                        .pathMatchers(HttpMethod.POST, "/api/v1/quotes").permitAll()
                        .pathMatchers(HttpMethod.GET, "/api/v1/quotes", "/api/v1/quotes/**").permitAll()
                        .pathMatchers(HttpMethod.GET, "/api/v1/zones", "/api/v1/zones/**").permitAll()

                        // Merchant onboarding registration and standing inspection (authenticated user)
                        .pathMatchers(HttpMethod.POST, "/api/v1/merchants").authenticated()
                        .pathMatchers(HttpMethod.GET, "/api/v1/merchants/me").hasAnyRole("CUSTOMER", "MERCHANT", "ADMIN")
                        .pathMatchers(HttpMethod.GET, "/api/v1/merchants/*/status").hasAnyRole("CUSTOMER", "MERCHANT", "ADMIN")

                        // Admin operations (merchant approval, administrative commands)
                        .pathMatchers(HttpMethod.POST, "/api/v1/merchants/*/approve").hasRole("ADMIN")
                        .pathMatchers("/api/v1/admin/**").hasRole("ADMIN")

                        // Dispatcher operations (courier assignment)
                        .pathMatchers(HttpMethod.POST, "/api/v1/assignments").hasAnyRole("DISPATCHER", "ADMIN")

                        // Courier operations (status patch, proof upload)
                        .pathMatchers(HttpMethod.PATCH, "/api/v1/parcels/*/status").hasRole("COURIER")
                        .pathMatchers(HttpMethod.POST, "/api/v1/parcels/*/proof").hasRole("COURIER")

                        // Customer parcel booking
                        .pathMatchers(HttpMethod.POST, "/api/v1/parcels").hasAnyRole("CUSTOMER", "MERCHANT", "ADMIN")

                        // Merchant machine order API (permits Bearer JWT with MERCHANT/ADMIN or valid X-API-Key)
                        .pathMatchers(HttpMethod.POST, "/api/v1/merchant/parcels").hasAnyRole("MERCHANT", "ADMIN")

                        // Merchant management endpoints (portal, API keys, webhooks)
                        .pathMatchers("/api/v1/merchants/**").hasAnyRole("MERCHANT", "ADMIN")

                        // Read-only composite GraphQL Query API
                        .pathMatchers(HttpMethod.POST, "/graphql").authenticated()

                        // Authenticated user profile
                        .pathMatchers("/api/v1/users/me").authenticated()

                        // Authenticated parcel details, receipts, and live SSE event streams
                        .pathMatchers("/api/v1/parcels/**").authenticated()

                        // All other business endpoints require authentication
                        .pathMatchers("/api/v1/**").authenticated()

                        // Fail-closed default-deny boundary
                        .anyExchange().denyAll()
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt
                                .jwtDecoder(jwtDecoder)
                                .jwtAuthenticationConverter(new KeycloakReactiveJwtAuthenticationConverter())
                        )
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                )
                .build();
    }

    @Bean
    public ReactiveJwtDecoder reactiveJwtDecoder(
            @Value("${KEYCLOAK_JWKS_URI:${spring.security.oauth2.resourceserver.jwt.jwk-set-uri:${swiftdrop.jwt.jwk-set-uri:${devarom.jwt.jwk-set-uri:http://localhost:8090/realms/swiftdrop/protocol/openid-connect/certs}}}}") String jwkSetUri,
            @Value("${KEYCLOAK_ISSUER_URI:${spring.security.oauth2.resourceserver.jwt.issuer-uri:${swiftdrop.jwt.issuer-uri:${devarom.jwt.issuer-uri:http://localhost:8090/realms/swiftdrop}}}}") String issuerUri,
            @Value("${JWT_AUDIENCE:${swiftdrop.jwt.audience:${devarom.jwt.audience:swiftdrop-api}}}") String audience) {
        NimbusReactiveJwtDecoder jwtDecoder = NimbusReactiveJwtDecoder.withJwkSetUri(jwkSetUri).build();
        OAuth2TokenValidator<Jwt> defaultValidator = JwtValidators.createDefaultWithIssuer(issuerUri);
        OAuth2TokenValidator<Jwt> audienceValidator = new AudienceValidator(audience);
        jwtDecoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(defaultValidator, audienceValidator));
        return jwtDecoder;
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${CORS_ALLOWED_ORIGIN:${swiftdrop.cors.allowed-origin:${devarom.cors.allowed-origin:http://localhost:5173}}}") String allowedOrigin) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(allowedOrigin));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept", "Origin", "X-Requested-With", "Idempotency-Key"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
