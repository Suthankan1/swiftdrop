package com.swiftdrop.graphql;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.swiftdrop.security.jwt.AudienceValidator;
import com.swiftdrop.security.jwt.SubjectValidator;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.main.allow-bean-definition-overriding=true", "debug=false", "logging.level.root=WARN"})
@AutoConfigureMockMvc
@Import(GraphqlEndpointSecurityTest.Keys.class)
class GraphqlEndpointSecurityTest {
    static final RSAKey TRUSTED = key();
    static final RSAKey FORGED = key();
    @Autowired MockMvc mvc;
    static RSAKey key() {
        try { return new RSAKeyGenerator(2048).generate(); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    @TestConfiguration(proxyBeanMethods = false)
    static class Keys {
        @Bean com.swiftdrop.graphql.resolver.RootQueryResolver providerDenialFixture() {
            return new com.swiftdrop.graphql.resolver.RootQueryResolver() {
                public String field() { return "dispatcherDashboard"; }
                public Object resolve(graphql.schema.DataFetchingEnvironment env) {
                    throw io.grpc.Status.PERMISSION_DENIED.withDescription("private-host SQL recipient-phone test-only").asRuntimeException();
                }
            };
        }
        @Bean JwtDecoder jwtDecoder() throws Exception {
            var decoder = NimbusJwtDecoder.withPublicKey(TRUSTED.toRSAPublicKey()).build();
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer("https://issuer.test"),
                    new AudienceValidator("swiftdrop-api"), new SubjectValidator()));
            return decoder;
        }
    }
    static String token(RSAKey key, String role, String issuer, String audience, long expiry) throws Exception {
        var claims = new JWTClaimsSet.Builder().subject("caller").issuer(issuer).audience(audience)
                .issueTime(Date.from(Instant.now().minusSeconds(120))).expirationTime(Date.from(Instant.now().plusSeconds(expiry)))
                .claim("realm_access", Map.of("roles", List.of(role))).build();
        var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
        jwt.sign(new RSASSASigner(key)); return jwt.serialize();
    }
    String valid(String role) throws Exception { return token(TRUSTED, role, "https://issuer.test", "swiftdrop-api", 600); }
    MvcResult query(String token, String query) throws Exception {
        var req = post("/graphql").contentType("application/json").content("{\"query\":\"" + query + "\"}");
        if (token != null) req.header("Authorization", "Bearer " + token);
        var result = mvc.perform(req).andReturn();
        return result.getRequest().isAsyncStarted() ? mvc.perform(asyncDispatch(result)).andReturn() : result;
    }
    @Test void directEndpointRejectsMissingMalformedAndInvalidSignedTokens() throws Exception {
        for (String bearer : new String[]{null, "broken", token(FORGED, "ADMIN", "https://issuer.test", "swiftdrop-api", 600),
                token(TRUSTED, "ADMIN", "https://wrong.test", "swiftdrop-api", 600),
                token(TRUSTED, "ADMIN", "https://issuer.test", "google", 600),
                token(TRUSTED, "ADMIN", "https://issuer.test", "swiftdrop-api", -600)}) {
            assertThat(query(bearer, "{adminDashboard{totalParcelsCount}}").getResponse().getStatus()).isEqualTo(401);
        }
    }
    @Test void authorizedMissingResolverFailsUnavailableWithoutFakeData() throws Exception {
        var result = query(valid("CUSTOMER"), "{customerDashboard{userId}}");
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString()).contains("UNAVAILABLE", "\"data\":null").doesNotContain("test-only", "localhost");
    }
    @Test void crossRoleFieldsFailBeforeUnavailableFallback() throws Exception {
        for (String[] pair : new String[][]{{"CUSTOMER", "adminDashboard{totalParcelsCount}"},
                {"COURIER", "merchantDashboard{merchantId}"}, {"MERCHANT", "customerDashboard{userId}"},
                {"DISPATCHER", "adminDashboard{totalParcelsCount}"}, {"unknown", "customerDashboard{userId}"}}) {
            assertThat(query(valid(pair[0]), "{" + pair[1] + "}").getResponse().getContentAsString()).contains("FORBIDDEN").doesNotContain("UNAVAILABLE");
        }
    }
    @Test void unapprovedOperationsFieldsAndIntrospectionAreRejected() throws Exception {
        for (String document : new String[]{"mutation{adminDashboard{totalParcelsCount}}", "subscription{adminDashboard{totalParcelsCount}}",
                "{publicTracking(trackingNumber:1){currentStatus}}", "{__schema{queryType{name}}}", "{"}) {
            assertThat(query(valid("ADMIN"), document).getResponse().getContentAsString()).contains("errors");
        }
    }
    @Test void forgedActorAndTenantHeadersAreRejected() throws Exception {
        for (String header : List.of("x-swiftdrop-actor-sub", "x-swiftdrop-actor-token", "x-swiftdrop-tenant-id")) {
            mvc.perform(post("/graphql").servletPath("/graphql").header("Authorization", "Bearer " + valid("ADMIN"))
                    .header(header, "forged").contentType("application/json").content("{\"query\":\"{__typename}\"}")).andExpect(status().isBadRequest());
        }
    }
    @Test void payloadLimitAndMethodBoundary() throws Exception {
        mvc.perform(post("/graphql").servletPath("/graphql").header("Authorization", "Bearer " + valid("ADMIN"))
                .contentType("application/json").content("x".repeat(65537))).andExpect(status().isPayloadTooLarge());
        mvc.perform(get("/graphql").header("Authorization", "Bearer " + valid("ADMIN"))).andExpect(status().isForbidden());
    }
    @Test void providerDenialIsSanitizedAndPaginationFailsBeforeProvider() throws Exception {
        String denied = query(valid("DISPATCHER"), "{dispatcherDashboard{pageInfo{pageSize}}}").getResponse().getContentAsString();
        assertThat(denied).contains("FORBIDDEN").doesNotContain("private-host", "SQL", "recipient-phone");
        String invalid = query(valid("DISPATCHER"), "{dispatcherDashboard(pagination:{size:51}){pageInfo{pageSize}}}").getResponse().getContentAsString();
        assertThat(invalid).contains("INVALID_ARGUMENT").doesNotContain("FORBIDDEN");
    }
    @Test void invalidJsonAndMissingDocumentAreClientErrors() throws Exception {
        for (String body : List.of("{", "{}", "[]")) {
            var result = mvc.perform(post("/graphql").header("Authorization", "Bearer " + valid("ADMIN"))
                    .contentType("application/json").content(body)).andReturn();
            if (result.getRequest().isAsyncStarted()) result = mvc.perform(asyncDispatch(result)).andReturn();
            assertThat(result.getResponse().getStatus()).isBetween(400, 499);
        }
    }
}
