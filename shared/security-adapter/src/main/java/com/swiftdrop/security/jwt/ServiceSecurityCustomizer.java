package com.swiftdrop.security.jwt;

import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;

/**
 * Functional callback interface enabling each business service (Delivery, Pricing, Communications,
 * GraphQL Query API, Accounts) to define domain-specific endpoint authorization rules while
 * inheriting the shared resource-server baseline and default-deny boundary.
 */
@FunctionalInterface
public interface ServiceSecurityCustomizer {

    void customize(AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry registry);
}
