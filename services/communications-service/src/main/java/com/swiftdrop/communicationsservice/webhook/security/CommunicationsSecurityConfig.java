package com.swiftdrop.communicationsservice.webhook.security;

import com.swiftdrop.security.jwt.ServiceSecurityCustomizer;
import com.swiftdrop.security.jwt.SwiftDropRoles;
import com.swiftdrop.security.jwt.SwiftDropSecurityConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;

/**
 * Service-specific security customization for Communications Service.
 * Implements strict default-deny and requires ROLE_MERCHANT for all webhook subscription endpoints.
 */
@Configuration(proxyBeanMethods = false)
@Import(SwiftDropSecurityConfiguration.class)
public class CommunicationsSecurityConfig {

    @Bean
    public ServiceSecurityCustomizer communicationsServiceSecurityCustomizer() {
        return auth -> auth
                .requestMatchers(HttpMethod.POST, "/api/v1/merchants/webhooks").hasRole(SwiftDropRoles.ROLE_MERCHANT)
                .requestMatchers(HttpMethod.GET, "/api/v1/merchants/webhooks", "/api/v1/merchants/webhooks/**").hasRole(SwiftDropRoles.ROLE_MERCHANT)
                .requestMatchers(HttpMethod.DELETE, "/api/v1/merchants/webhooks/*").hasRole(SwiftDropRoles.ROLE_MERCHANT);
    }
}
