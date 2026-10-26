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

    @Bean
    public ServiceSecurityCustomizer pricingSecurityCustomizer() {
        return auth -> auth
                .requestMatchers(HttpMethod.POST, "/api/v1/quotes").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/quotes/*").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/zones").permitAll();
    }
}
