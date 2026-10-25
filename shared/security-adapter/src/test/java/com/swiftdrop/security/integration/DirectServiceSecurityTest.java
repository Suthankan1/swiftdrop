package com.swiftdrop.security.integration;

import com.swiftdrop.security.jwt.ServiceSecurityCustomizer;
import com.swiftdrop.security.jwt.SwiftDropSecurityConfiguration;
import com.swiftdrop.security.testutil.JwtTestHelper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Validates requirement 5.B and 8.A: Independent resource-server enforcement.
 * Direct requests to a backend service are never trusted merely because they bypass the Gateway or provide forwarding headers.
 */
@WebMvcTest(controllers = DirectServiceSecurityTest.DirectServiceEndpointController.class,
        excludeAutoConfiguration = org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration.class)
@Import({SwiftDropSecurityConfiguration.class, DirectServiceSecurityTest.DirectSecurityTestConfig.class})
@TestPropertySource(properties = "spring.main.allow-bean-definition-overriding=true")
class DirectServiceSecurityTest {

    private static final JwtTestHelper jwtHelper = new JwtTestHelper();

    @Autowired
    private MockMvc mvc;

    @TestConfiguration
    static class DirectSecurityTestConfig {
        @Bean
        public DirectServiceEndpointController directServiceEndpointController() {
            return new DirectServiceEndpointController();
        }

        @Bean
        public JwtDecoder jwtDecoder() {
            return jwtHelper.createUserJwtDecoder();
        }

        @Bean
        public ServiceSecurityCustomizer directServiceCustomizer() {
            return auth -> auth
                    .requestMatchers("/api/v1/direct/parcels/**").hasAnyRole("CUSTOMER", "MERCHANT", "COURIER", "DISPATCHER", "ADMIN")
                    .requestMatchers("/api/v1/direct/internal/**").hasRole("ADMIN");
        }
    }

    @RestController
    static class DirectServiceEndpointController {
        @GetMapping("/api/v1/direct/parcels/sample")
        public ResponseEntity<Map<String, String>> sampleParcel() {
            return ResponseEntity.ok(Map.of("parcelId", "p-12345", "status", "CREATED"));
        }

        @GetMapping("/api/v1/direct/internal/config")
        public ResponseEntity<Map<String, String>> internalConfig() {
            return ResponseEntity.ok(Map.of("environment", "production"));
        }
    }

    @Test
    @DisplayName("Direct service access without bearer token fails with 401 Unauthorized")
    void testDirectAccessWithoutTokenFails() throws Exception {
        mvc.perform(get("/api/v1/direct/parcels/sample"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Unauthorized"))
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("Direct service access with forged gateway forwarding headers fails with 401 Unauthorized")
    void testDirectAccessWithForgedGatewayHeadersFails() throws Exception {
        mvc.perform(get("/api/v1/direct/parcels/sample")
                        .header("X-Forwarded-For", "10.0.0.1")
                        .header("X-Forwarded-Host", "gateway.swiftdrop.internal")
                        .header("X-Gateway-Auth", "true")
                        .header("X-User-Sub", "admin-user")
                        .header("X-User-Roles", "ADMIN"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("Direct service access with valid Keycloak token succeeds independently")
    void testDirectAccessWithValidTokenSucceeds() throws Exception {
        String token = jwtHelper.createSignedUserToken("customer-uuid-99", List.of("CUSTOMER"));

        mvc.perform(get("/api/v1/direct/parcels/sample")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parcelId").value("p-12345"))
                .andExpect(jsonPath("$.status").value("CREATED"));
    }

    @Test
    @DisplayName("Direct service access with valid token lacking required role fails with 403 Forbidden")
    void testDirectAccessWithInsufficientRoleFails() throws Exception {
        String customerToken = jwtHelper.createSignedUserToken("customer-uuid-99", List.of("CUSTOMER"));

        mvc.perform(get("/api/v1/direct/internal/config")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }
}
