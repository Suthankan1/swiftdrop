package com.swiftdrop.security.jwt;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Reusable, production-grade Spring Security Resource Server configuration for SwiftDrop services.
 * Enforces cryptographic JWT verification, statelessness, default-deny boundaries, RFC 7807 error responses,
 * and service-specific domain authorization hooks.
 */
@Configuration(proxyBeanMethods = false)
public class SwiftDropSecurityConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public SecurityFilterChain swiftDropSecurityFilterChain(
            HttpSecurity http,
            JwtDecoder jwtDecoder,
            KeycloakJwtAuthenticationConverter jwtAuthenticationConverter,
            ProblemDetailAuthenticationEntryPoint authenticationEntryPoint,
            ProblemDetailAccessDeniedHandler accessDeniedHandler,
            ObjectProvider<ServiceSecurityCustomizer> customizerProvider) throws Exception {

        ServiceSecurityCustomizer customizer = customizerProvider.getIfAvailable();

        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .anonymous(Customizer.withDefaults())
                .authorizeHttpRequests(auth -> {
                    // Universal health probe permitAll
                    auth.requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll();

                    // Apply domain-specific policy hooks from owning service if registered
                    if (customizer != null) {
                        customizer.customize(auth);
                    }

                    // Strict default-deny for all unmatched or unconfigured endpoints
                    auth.anyRequest().denyAll();
                })
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt
                                .decoder(jwtDecoder)
                                .jwtAuthenticationConverter(jwtAuthenticationConverter))
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .build();
    }

    @Bean
    @ConditionalOnMissingBean
    public JwtDecoder jwtDecoder(
            @Value("${KEYCLOAK_JWKS_URI:${spring.security.oauth2.resourceserver.jwt.jwk-set-uri:${swiftdrop.jwt.jwk-set-uri:${devarom.jwt.jwk-set-uri:http://localhost:8090/realms/swiftdrop/protocol/openid-connect/certs}}}}") String jwkSetUri,
            @Value("${KEYCLOAK_ISSUER_URI:${spring.security.oauth2.resourceserver.jwt.issuer-uri:${swiftdrop.jwt.issuer-uri:${devarom.jwt.issuer-uri:http://localhost:8090/realms/swiftdrop}}}}") String issuerUri,
            @Value("${JWT_AUDIENCE:${swiftdrop.jwt.audience:${devarom.jwt.audience:swiftdrop-api}}}") String audience) {
        return SwiftDropJwtDecoderFactory.createDecoder(jwkSetUri, issuerUri, audience);
    }

    @Bean
    @ConditionalOnMissingBean
    public KeycloakJwtAuthenticationConverter keycloakJwtAuthenticationConverter() {
        return new KeycloakJwtAuthenticationConverter();
    }

    @Bean
    @ConditionalOnMissingBean
    public ProblemDetailAuthenticationEntryPoint problemDetailAuthenticationEntryPoint() {
        return new ProblemDetailAuthenticationEntryPoint();
    }

    @Bean
    @ConditionalOnMissingBean
    public ProblemDetailAccessDeniedHandler problemDetailAccessDeniedHandler() {
        return new ProblemDetailAccessDeniedHandler();
    }
}
