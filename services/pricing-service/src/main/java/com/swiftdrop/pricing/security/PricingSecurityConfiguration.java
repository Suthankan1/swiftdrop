package com.swiftdrop.pricing.security;

import com.swiftdrop.security.jwt.ServiceSecurityCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;

/**
 * Service-specific security customization for Pricing & Zones service.
 *
 * Implements the reviewed OpenAPI contract:
 * - POST /api/v1/quotes: PermitAll (anonymous rate calculation or authenticated quote binding)
 * - GET /api/v1/quotes/{quoteId}: PermitAll (ownership / anonymous policy enforced at service layer)
 * - GET /api/v1/zones: PermitAll (public zone listing)
 * - /actuator/health: PermitAll (health probes)
 * - Any other endpoint: DenyAll
 */
@Configuration(proxyBeanMethods = false)
public class PricingSecurityConfiguration {

    /** Separate holder keeps the HTTP audience validator out of the existing gRPC machine-token decoder. */
    public record HttpJwtValidation(org.springframework.security.oauth2.jwt.JwtDecoder decoder) { }

    @Bean
    public HttpJwtValidation pricingHttpJwtValidation(
            @org.springframework.beans.factory.annotation.Value("${swiftdrop.jwt.jwk-set-uri}") String jwks,
            @org.springframework.beans.factory.annotation.Value("${swiftdrop.jwt.issuer-uri}") String issuer,
            @org.springframework.beans.factory.annotation.Value("${swiftdrop.jwt.audience}") String audience) {
        return new HttpJwtValidation(com.swiftdrop.security.jwt.SwiftDropJwtDecoderFactory.createDecoder(jwks, issuer, audience));
    }

    @Bean
    public org.springframework.security.web.SecurityFilterChain pricingHttpSecurity(
            org.springframework.security.config.annotation.web.builders.HttpSecurity http, HttpJwtValidation validation,
            org.springframework.beans.factory.ObjectProvider<ServiceSecurityCustomizer> customizers) throws Exception {
        // Reuse the shared stateless JWT/default-deny chain with a dedicated HTTP decoder.
        // Leave Boot's existing JwtDecoder bean and all #114 gRPC wiring unchanged.
        return new com.swiftdrop.security.jwt.SwiftDropSecurityConfiguration().swiftDropSecurityFilterChain(
                http, validation.decoder(), new com.swiftdrop.security.jwt.KeycloakJwtAuthenticationConverter(),
                new com.swiftdrop.security.jwt.ProblemDetailAuthenticationEntryPoint(),
                new com.swiftdrop.security.jwt.ProblemDetailAccessDeniedHandler(), customizers);
    }

    @Bean
    public ServiceSecurityCustomizer pricingSecurityCustomizer() {
        return auth -> auth
                .requestMatchers("/api/v1/admin/pricing/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/v1/quotes").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/quotes/*").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/zones").permitAll();
    }
}
