package com.swiftdrop.security.jwt;

import com.swiftdrop.security.testutil.JwtTestHelper;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.springframework.test.context.TestPropertySource;

@WebMvcTest(controllers = JwtValidationTest.TestProtectedController.class,
        excludeAutoConfiguration = org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration.class)
@Import({SwiftDropSecurityConfiguration.class, JwtValidationTest.TestSecurityConfig.class})
@TestPropertySource(properties = "spring.main.allow-bean-definition-overriding=true")
class JwtValidationTest {

    private static final JwtTestHelper jwtHelper = new JwtTestHelper();
    private static final AtomicInteger businessHandlerInvocations = new AtomicInteger(0);

    @Autowired
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        businessHandlerInvocations.set(0);
    }

    @TestConfiguration
    static class TestSecurityConfig {
        @Bean
        public TestProtectedController testProtectedController() {
            return new TestProtectedController();
        }

        @Bean
        public JwtDecoder jwtDecoder() {
            return jwtHelper.createUserJwtDecoder();
        }

        @Bean
        public ServiceSecurityCustomizer serviceSecurityCustomizer() {
            return auth -> auth
                    .requestMatchers("/api/v1/test/customer-only").hasRole("CUSTOMER")
                    .requestMatchers("/api/v1/test/admin-only").hasRole("ADMIN")
                    .requestMatchers("/api/v1/test/any-authenticated").authenticated();
        }
    }

    @RestController
    static class TestProtectedController {
        @GetMapping("/api/v1/test/customer-only")
        public ResponseEntity<Map<String, Object>> customerEndpoint(@AuthenticationPrincipal Object principal) {
            businessHandlerInvocations.incrementAndGet();
            return ResponseEntity.ok(Map.of("status", "ok", "role", "CUSTOMER"));
        }

        @GetMapping("/api/v1/test/admin-only")
        public ResponseEntity<Map<String, Object>> adminEndpoint(@AuthenticationPrincipal Object principal) {
            businessHandlerInvocations.incrementAndGet();
            return ResponseEntity.ok(Map.of("status", "ok", "role", "ADMIN"));
        }

        @GetMapping("/api/v1/test/any-authenticated")
        public ResponseEntity<Map<String, Object>> anyAuthenticatedEndpoint(@AuthenticationPrincipal Object principal) {
            businessHandlerInvocations.incrementAndGet();
            return ResponseEntity.ok(Map.of("status", "ok"));
        }
    }

    @Test
    @DisplayName("Valid authorized token: returns 200 OK and executes business handler")
    void testValidAuthorizedToken() throws Exception {
        String token = jwtHelper.createSignedUserToken("user-uuid-1", List.of("CUSTOMER"));

        mvc.perform(get("/api/v1/test/customer-only")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.role").value("CUSTOMER"));

        assertThat(businessHandlerInvocations.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Missing token: returns 401 Problem Detail and never executes handler")
    void testMissingToken() throws Exception {
        mvc.perform(get("/api/v1/test/customer-only"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(jsonPath("$.title").value("Unauthorized"))
                .andExpect(jsonPath("$.status").value(401));

        assertThat(businessHandlerInvocations.get()).isZero();
    }

    @Test
    @DisplayName("Malformed token: returns 401 Problem Detail")
    void testMalformedToken() throws Exception {
        mvc.perform(get("/api/v1/test/customer-only")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer not.a.valid.jwt.payload"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401));

        assertThat(businessHandlerInvocations.get()).isZero();
    }

    @Test
    @DisplayName("Invalid signature: signed by untrusted key returns 401 Problem Detail")
    void testInvalidSignature() throws Exception {
        String forgedToken = jwtHelper.createUntrustedSignedUserToken("user-uuid-1", List.of("CUSTOMER"));

        mvc.perform(get("/api/v1/test/customer-only")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + forgedToken))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401));

        assertThat(businessHandlerInvocations.get()).isZero();
    }

    @Test
    @DisplayName("Untrusted issuer: returns 401 Problem Detail")
    void testUntrustedIssuer() throws Exception {
        String badIssuerToken = jwtHelper.createSignedUserToken(
                "user-uuid-1",
                List.of("CUSTOMER"),
                JwtTestHelper.DEFAULT_USER_AUDIENCE,
                "http://malicious-issuer.com/realms/fake",
                Instant.now().plus(1, ChronoUnit.HOURS)
        );

        mvc.perform(get("/api/v1/test/customer-only")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + badIssuerToken))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401));

        assertThat(businessHandlerInvocations.get()).isZero();
    }

    @Test
    @DisplayName("Incorrect audience: returns 401 Problem Detail")
    void testIncorrectAudience() throws Exception {
        String wrongAudienceToken = jwtHelper.createSignedUserToken(
                "user-uuid-1",
                List.of("CUSTOMER"),
                "wrong-api-audience",
                JwtTestHelper.DEFAULT_ISSUER,
                Instant.now().plus(1, ChronoUnit.HOURS)
        );

        mvc.perform(get("/api/v1/test/customer-only")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + wrongAudienceToken))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401));

        assertThat(businessHandlerInvocations.get()).isZero();
    }

    @Test
    @DisplayName("Expired token: returns 401 Problem Detail")
    void testExpiredToken() throws Exception {
        String expiredToken = jwtHelper.createExpiredUserToken("user-uuid-1", List.of("CUSTOMER"));

        mvc.perform(get("/api/v1/test/customer-only")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + expiredToken))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401));

        assertThat(businessHandlerInvocations.get()).isZero();
    }

    @Test
    @DisplayName("Token not yet valid (nbf in future): returns 401 Problem Detail")
    void testTokenNotYetValid() throws Exception {
        String nbfToken = jwtHelper.createNotYetValidUserToken("user-uuid-1", List.of("CUSTOMER"));

        mvc.perform(get("/api/v1/test/customer-only")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + nbfToken))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401));

        assertThat(businessHandlerInvocations.get()).isZero();
    }

    @Test
    @DisplayName("Missing required role: customer accessing admin endpoint returns 403 Forbidden")
    void testMissingRequiredRole() throws Exception {
        String customerToken = jwtHelper.createSignedUserToken("customer-uuid-1", List.of("CUSTOMER"));

        mvc.perform(get("/api/v1/test/admin-only")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken))
                .andExpect(status().isForbidden())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Forbidden"))
                .andExpect(jsonPath("$.status").value(403));

        assertThat(businessHandlerInvocations.get()).isZero();
    }

    @Test
    @DisplayName("Unknown or unapproved role: does not grant access to protected endpoints")
    void testUnknownRoleIgnored() throws Exception {
        // Token contains fake or Keycloak default roles but no approved SwiftDrop role
        String token = jwtHelper.createSignedUserToken("fake-admin-uuid", List.of("SUPERUSER", "offline_access", "uma_authorization"));

        mvc.perform(get("/api/v1/test/admin-only")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));

        assertThat(businessHandlerInvocations.get()).isZero();
    }

    @Test
    @DisplayName("Attempted authentication using only forged forwarding header: fails with 401")
    void testForgedForwardingHeadersRejected() throws Exception {
        mvc.perform(get("/api/v1/test/admin-only")
                        .header("X-Forwarded-User", "admin-user")
                        .header("X-User-Roles", "ADMIN")
                        .header("x-swiftdrop-actor-roles", "ADMIN")
                        .header("x-swiftdrop-actor-sub", "admin-uuid"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));

        assertThat(businessHandlerInvocations.get()).isZero();
    }
}
