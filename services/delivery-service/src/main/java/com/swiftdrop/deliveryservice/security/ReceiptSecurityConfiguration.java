package com.swiftdrop.deliveryservice.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

import java.io.IOException;

/**
 * OAuth2 Resource Server security configuration for Delivery Operations Service.
 * Independently validates Keycloak signed JWTs, issuer, audience ("swiftdrop-api"), subject ("sub"),
 * and enforces fail-closed edge authorization.
 */
@Configuration(proxyBeanMethods = false)
public class ReceiptSecurityConfiguration {

    @Bean
    @Order(1)
    SecurityFilterChain receiptSecurity(HttpSecurity http,
                                        JwtDecoder jwtDecoder,
                                        KeycloakJwtAuthenticationConverter jwtAuthenticationConverter,
                                        org.springframework.beans.factory.ObjectProvider<com.swiftdrop.deliveryservice.security.merchant.AccountsMerchantKeyValidator> accountsMerchantKeyValidatorProvider) throws Exception {
        var keyValidator = accountsMerchantKeyValidatorProvider.getIfAvailable();
        if (keyValidator != null) {
            http.addFilterBefore(
                    new com.swiftdrop.deliveryservice.security.merchant.MerchantApiKeyAuthenticationFilter(keyValidator),
                    org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter.class
            );
        }
        return http
                .securityMatcher("/api/v1/parcels", "/api/v1/parcels/**", "/api/v1/merchant/parcels", "/api/v1/merchant/parcels/**", "/api/v1/assignments", "/api/v1/assignments/**", "/actuator/health", "/actuator/health/**", "/api/v1/tracking", "/api/v1/tracking/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .anonymous(Customizer.withDefaults())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/tracking", "/api/v1/tracking/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/merchant/parcels").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/parcels/*/receipt").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/parcels/*/events").authenticated()
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/parcels/*/status").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/parcels/*/proof").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/parcels/*/proof", "/api/v1/parcels/*/proof/**").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/assignments").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/assignments", "/api/v1/assignments/**").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/parcels", "/api/v1/parcels/*", "/api/v1/parcels/*/history").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/parcels").hasAnyRole("CUSTOMER", "MERCHANT", "ADMIN")
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt
                                .decoder(jwtDecoder)
                                .jwtAuthenticationConverter(jwtAuthenticationConverter))
                        .authenticationEntryPoint((request, response, failure) -> writeProblem(request, response, 401, "Unauthorized", "Bearer token is missing, expired, or invalid."))
                        .accessDeniedHandler((request, response, failure) -> writeProblem(request, response, 403, "Forbidden", "Access is denied.")))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, failure) -> writeProblem(request, response, 401, "Unauthorized", "Bearer token is missing, expired, or invalid."))
                        .accessDeniedHandler((request, response, failure) -> writeProblem(request, response, 403, "Forbidden", "Access is denied.")))
                .build();
    }

    @Bean
    public JwtDecoder jwtDecoder(
            @Value("${KEYCLOAK_JWKS_URI:${spring.security.oauth2.resourceserver.jwt.jwk-set-uri:${swiftdrop.jwt.jwk-set-uri:${devarom.jwt.jwk-set-uri:http://localhost:8090/realms/swiftdrop/protocol/openid-connect/certs}}}}") String jwkSetUri,
            @Value("${KEYCLOAK_ISSUER_URI:${spring.security.oauth2.resourceserver.jwt.issuer-uri:${swiftdrop.jwt.issuer-uri:${devarom.jwt.issuer-uri:http://localhost:8090/realms/swiftdrop}}}}") String issuerUri,
            @Value("${JWT_AUDIENCE:${swiftdrop.jwt.audience:${devarom.jwt.audience:swiftdrop-api}}}") String audience) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        OAuth2TokenValidator<Jwt> defaultValidator = JwtValidators.createDefaultWithIssuer(issuerUri);
        OAuth2TokenValidator<Jwt> audienceValidator = new AudienceValidator(audience);
        OAuth2TokenValidator<Jwt> subjectValidator = new SubjectValidator();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(defaultValidator, audienceValidator, subjectValidator));
        return decoder;
    }

    @Bean
    public KeycloakJwtAuthenticationConverter keycloakJwtAuthenticationConverter() {
        return new KeycloakJwtAuthenticationConverter();
    }

    private static void writeProblem(HttpServletRequest request, HttpServletResponse response, int status, String title, String detail) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (status == 401 && (authHeader == null || !authHeader.regionMatches(true, 0, "Basic", 0, 5))) {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        }
        String path = request.getRequestURI();
        String json = "{\"type\":\"about:blank\"," +
                "\"title\":\"" + escapeJson(title) + "\"," +
                "\"status\":" + status + "," +
                "\"detail\":\"" + escapeJson(detail) + "\"," +
                "\"instance\":\"" + escapeJson(path) + "\"}";
        response.getWriter().write(json);
    }

    private static String escapeJson(String input) {
        if (input == null) return "";
        return input.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
