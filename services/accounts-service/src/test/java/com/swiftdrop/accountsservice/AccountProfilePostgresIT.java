package com.swiftdrop.accountsservice;

import com.swiftdrop.accountsservice.identity.TrustedIdentity;
import com.swiftdrop.accountsservice.profile.AccountProfile;
import com.swiftdrop.accountsservice.profile.AccountProfileRepository;
import com.swiftdrop.accountsservice.profile.CurrentUserProfileService;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import jakarta.persistence.EntityManager;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AccountProfilePostgresIT {

    private static MockWebServer authServer;
    private static RSAKey trustedRsaKey;
    private static RSAKey untrustedRsaKey;
    private static String issuerUrl;

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("accounts_db");

    @BeforeAll
    static void setUpAuthServer() throws Exception {
        trustedRsaKey = new RSAKeyGenerator(2048)
                .keyID("trusted-postgres-it-key")
                .generate();

        untrustedRsaKey = new RSAKeyGenerator(2048)
                .keyID("untrusted-postgres-it-key")
                .generate();

        authServer = new MockWebServer();
        authServer.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                if ("/protocol/openid-connect/certs".equals(request.getPath())) {
                    JWKSet jwkSet = new JWKSet(trustedRsaKey.toPublicJWK());
                    return new MockResponse()
                            .setResponseCode(200)
                            .setHeader("Content-Type", "application/json")
                            .setBody(jwkSet.toString());
                }
                return new MockResponse().setResponseCode(404);
            }
        });
        authServer.start();
        issuerUrl = authServer.url("/realms/devarom").toString().replaceAll("/$", "");
    }

    @AfterAll
    static void tearDownAuthServer() throws IOException {
        if (authServer != null) {
            authServer.shutdown();
        }
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("devarom.jwt.jwk-set-uri", () -> authServer.url("/protocol/openid-connect/certs").toString());
        registry.add("devarom.jwt.issuer-uri", () -> issuerUrl);
        registry.add("devarom.jwt.audience", () -> "devarom-api");
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> authServer.url("/protocol/openid-connect/certs").toString());
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> issuerUrl);
    }

    @Autowired CurrentUserProfileService service;
    @Autowired AccountProfileRepository repository;
    @Autowired JdbcTemplate jdbc;
    @Autowired Flyway flyway;
    @Autowired EntityManager entityManager;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired org.springframework.context.ApplicationContext context;

    @org.springframework.boot.test.web.server.LocalServerPort
    int port;

    private String createSignedToken(String subject, Object roles, String name, String email,
                                     String audience, String issuer, boolean expired, RSAKey signingKey) throws Exception {
        Instant now = Instant.now();
        Instant exp = expired ? now.minus(5, ChronoUnit.MINUTES) : now.plus(5, ChronoUnit.MINUTES);
        JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
                .issuer(issuer != null ? issuer : issuerUrl)
                .issueTime(Date.from(now.minus(1, ChronoUnit.MINUTES)))
                .expirationTime(Date.from(exp));

        if (subject != null) builder.subject(subject);
        if (audience != null) builder.audience(List.of(audience));
        if (roles != null) builder.claim("roles", roles);
        if (name != null) builder.claim("name", name);
        if (email != null) builder.claim("email", email);

        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256)
                .keyID(signingKey.getKeyID())
                .build();
        SignedJWT signed = new SignedJWT(header, builder.build());
        signed.sign(new RSASSASigner(signingKey.toRSAPrivateKey()));
        return signed.serialize();
    }

    @Test
    void actualServerStartsWithPublicHealthAndRejectsAnonymousAndSpoofedProfileRequests() throws Exception {
        assertThat(context.getBeansOfType(org.springframework.security.core.userdetails.UserDetailsService.class)).isEmpty();
        try (var client = java.net.http.HttpClient.newHttpClient()) {
            var health = client.send(java.net.http.HttpRequest.newBuilder(
                    java.net.URI.create("http://localhost:" + port + "/actuator/health")).build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            assertThat(health.statusCode()).isEqualTo(200);
            assertThat(tools.jackson.databind.json.JsonMapper.builder().build()
                    .readTree(health.body()).get("status").asString()).isEqualTo("UP");
            for (boolean spoofed : List.of(false, true)) {
                var request = java.net.http.HttpRequest.newBuilder(
                        java.net.URI.create("http://localhost:" + port + "/api/v1/users/me"));
                if (spoofed) request.header("X-Keycloak-Sub", "synthetic-other")
                        .header("Authorization", "Bearer unverified");
                var response = client.send(request.build(), java.net.http.HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(401);
                assertThat(response.body()).doesNotContain("synthetic-other", "SELECT", "stackTrace");
                assertThat(response.headers().firstValue("content-type").orElse("")).startsWith("application/problem+json");
            }
        }
    }

    @Test
    void authenticatedHttpProfileIsolationAndPersistence() throws Exception {
        try (var client = java.net.http.HttpClient.newHttpClient()) {
            String subA = "customer-a-" + UUID.randomUUID();
            String tokenA = createSignedToken(subA, List.of("CUSTOMER"), "Customer A", "a@example.test",
                    "devarom-api", issuerUrl, false, trustedRsaKey);

            // A. Customer A authenticates and gets profile A
            var reqA = java.net.http.HttpRequest.newBuilder(
                    java.net.URI.create("http://localhost:" + port + "/api/v1/users/me"))
                    .header("Authorization", "Bearer " + tokenA)
                    .build();
            var respA = client.send(reqA, java.net.http.HttpResponse.BodyHandlers.ofString());
            assertThat(respA.statusCode()).isEqualTo(200);
            assertThat(respA.headers().firstValue("Cache-Control").orElse("")).isEqualTo("no-store");

            var jsonMapper = tools.jackson.databind.json.JsonMapper.builder().build();
            var jsonA = jsonMapper.readTree(respA.body());
            String idA = jsonA.get("id").asString();
            assertThat(idA).isNotBlank();
            assertThat(jsonA.get("keycloakSub").asString()).isEqualTo(subA);
            assertThat(jsonA.get("email").asString()).isEqualTo("a@example.test");
            assertThat(jsonA.get("fullName").asString()).isEqualTo("Customer A");
            // H. Valid roles come from verified claims
            assertThat(jsonA.get("roles").get(0).asString()).isEqualTo("CUSTOMER");
            // I. merchantProfile remains null before Issue #8
            assertThat(jsonA.get("merchantProfile").isNull()).isTrue();

            // Verify in PostgreSQL:
            var dbProfileA = repository.findByKeycloakSub(subA).orElseThrow();
            assertThat(dbProfileA.getId().toString()).isEqualTo(idA);

            // B. Repeated Customer A request returns the same ID
            var respA2 = client.send(reqA, java.net.http.HttpResponse.BodyHandlers.ofString());
            assertThat(respA2.statusCode()).isEqualTo(200);
            var jsonA2 = jsonMapper.readTree(respA2.body());
            assertThat(jsonA2.get("id").asString()).isEqualTo(idA);

            // C. Customer B authenticates and gets a different ID
            String subB = "customer-b-" + UUID.randomUUID();
            String tokenB = createSignedToken(subB, List.of("CUSTOMER"), "Customer B", "b@example.test",
                    "devarom-api", issuerUrl, false, trustedRsaKey);
            var reqB = java.net.http.HttpRequest.newBuilder(
                    java.net.URI.create("http://localhost:" + port + "/api/v1/users/me"))
                    .header("Authorization", "Bearer " + tokenB)
                    .build();
            var respB = client.send(reqB, java.net.http.HttpResponse.BodyHandlers.ofString());
            assertThat(respB.statusCode()).isEqualTo(200);
            var jsonB = jsonMapper.readTree(respB.body());
            String idB = jsonB.get("id").asString();
            assertThat(idB).isNotEqualTo(idA);
            assertThat(jsonB.get("keycloakSub").asString()).isEqualTo(subB);

            // D. Customer A cannot select or retrieve Customer B's profile using query parameters or spoofed headers
            var spoofReq = java.net.http.HttpRequest.newBuilder(
                    java.net.URI.create("http://localhost:" + port + "/api/v1/users/me?subject=" + subB + "&id=" + idB))
                    .header("Authorization", "Bearer " + tokenA)
                    .header("X-User-Id", subB)
                    .header("X-Keycloak-Sub", subB)
                    .header("X-Roles", "ADMIN")
                    .header("X-User-Email", "b@example.test")
                    .build();
            var spoofResp = client.send(spoofReq, java.net.http.HttpResponse.BodyHandlers.ofString());
            assertThat(spoofResp.statusCode()).isEqualTo(200);
            var spoofJson = jsonMapper.readTree(spoofResp.body());
            assertThat(spoofJson.get("id").asString()).isEqualTo(idA);
            assertThat(spoofJson.get("keycloakSub").asString()).isEqualTo(subA);
            assertThat(spoofJson.get("roles").get(0).asString()).isEqualTo("CUSTOMER");
        }
    }

    @Test
    void concurrentAuthenticatedFirstRequestsCreateOnePersistedProfile() throws Exception {
        String sub = "concurrent-sub-" + UUID.randomUUID();
        String token = createSignedToken(sub, List.of("CUSTOMER"), "Concurrent User", "conc@example.test",
                "devarom-api", issuerUrl, false, trustedRsaKey);

        var ready = new CountDownLatch(8);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(8);
             var client = java.net.http.HttpClient.newHttpClient()) {
            var futures = IntStream.range(0, 8).mapToObj(i -> pool.submit(() -> {
                ready.countDown();
                if (!start.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Start timeout");
                var request = java.net.http.HttpRequest.newBuilder(
                        java.net.URI.create("http://localhost:" + port + "/api/v1/users/me"))
                        .header("Authorization", "Bearer " + token)
                        .build();
                var resp = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
                assertThat(resp.statusCode()).isEqualTo(200);
                return tools.jackson.databind.json.JsonMapper.builder().build()
                        .readTree(resp.body()).get("id").asString();
            })).toList();

            assertThat(ready.await(15, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            String firstId = futures.getFirst().get(30, TimeUnit.SECONDS);
            for (var f : futures) {
                assertThat(f.get(30, TimeUnit.SECONDS)).isEqualTo(firstId);
            }
        }
        assertThat(count(sub)).isEqualTo(1);
    }

    @Test
    void rejectedTokensNeverCreateProfileRowInDatabase() throws Exception {
        String subExpired = "sub-exp-" + UUID.randomUUID();
        String expiredToken = createSignedToken(subExpired, List.of("CUSTOMER"), "Expired", "exp@example.test",
                "devarom-api", issuerUrl, true, trustedRsaKey);

        String subUntrusted = "sub-untrusted-" + UUID.randomUUID();
        String untrustedToken = createSignedToken(subUntrusted, List.of("CUSTOMER"), "Untrusted", "untrusted@example.test",
                "devarom-api", issuerUrl, false, untrustedRsaKey);

        String subWrongAud = "sub-wrong-aud-" + UUID.randomUUID();
        String wrongAudToken = createSignedToken(subWrongAud, List.of("CUSTOMER"), "Wrong Aud", "aud@example.test",
                "wrong-audience", issuerUrl, false, trustedRsaKey);

        String subWrongRole = "sub-wrong-role-" + UUID.randomUUID();
        String wrongRoleToken = createSignedToken(subWrongRole, List.of("UNKNOWN_ROLE"), "Wrong Role", "role@example.test",
                "devarom-api", issuerUrl, false, trustedRsaKey);

        try (var client = java.net.http.HttpClient.newHttpClient()) {
            for (String token : List.of(expiredToken, untrustedToken, wrongAudToken, "malformed.jwt.token")) {
                var req = java.net.http.HttpRequest.newBuilder(
                        java.net.URI.create("http://localhost:" + port + "/api/v1/users/me"))
                        .header("Authorization", "Bearer " + token)
                        .build();
                var resp = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
                assertThat(resp.statusCode()).isEqualTo(401);
            }

            // Wrong role -> 403
            var reqRole = java.net.http.HttpRequest.newBuilder(
                    java.net.URI.create("http://localhost:" + port + "/api/v1/users/me"))
                    .header("Authorization", "Bearer " + wrongRoleToken)
                    .build();
            var respRole = client.send(reqRole, java.net.http.HttpResponse.BodyHandlers.ofString());
            assertThat(respRole.statusCode()).isEqualTo(403);
        }

        // Verify none of these created a row in account_profiles
        assertThat(count(subExpired)).isZero();
        assertThat(count(subUntrusted)).isZero();
        assertThat(count(subWrongAud)).isZero();
        assertThat(count(subWrongRole)).isZero();
    }

    @Test
    void authenticatedMerchantGetsMerchantRoleWithNullMerchantProfile() throws Exception {
        String sub = "merchant-sub-" + UUID.randomUUID();
        String token = createSignedToken(sub, List.of("MERCHANT"), "Merchant User", "merchant@test.test",
                "devarom-api", issuerUrl, false, trustedRsaKey);

        try (var client = java.net.http.HttpClient.newHttpClient()) {
            var req = java.net.http.HttpRequest.newBuilder(
                    java.net.URI.create("http://localhost:" + port + "/api/v1/users/me"))
                    .header("Authorization", "Bearer " + token)
                    .build();
            var resp = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
            assertThat(resp.statusCode()).isEqualTo(200);

            var json = tools.jackson.databind.json.JsonMapper.builder().build().readTree(resp.body());
            assertThat(json.get("keycloakSub").asString()).isEqualTo(sub);
            assertThat(json.get("roles").get(0).asString()).isEqualTo("MERCHANT");
            assertThat(json.get("merchantProfile").isNull()).isTrue();
        }
        assertThat(count(sub)).isEqualTo(1);
    }

    private TrustedIdentity identity() {
        return new TrustedIdentity("synthetic-" + UUID.randomUUID(), "customer@example.test",
                "Synthetic Customer", List.of("CUSTOMER"));
    }

    @Test
    void migratesCleanPostgresAndValidatesSchema() {
        assertThat(flyway.info().pending()).isEmpty();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("3");
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).isEqualTo("accounts_db");
    }

    @Test
    void createsAndFindsPersistedProfileAfterClearingJpaContext() {
        var identity = identity();
        var created = service.getOrCreate(identity);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            entityManager.clear();
            var reloaded = repository.findByKeycloakSub(identity.subject()).orElseThrow();
            assertThat(reloaded.getId()).isEqualTo(created.id());
            assertThat(reloaded.getEmail()).isEqualTo(identity.email());
            assertThat(reloaded.getFullName()).isEqualTo(identity.fullName());
            assertThat(reloaded.getCreatedAt()).isEqualTo(created.createdAt());
        });
        assertThat(created.id()).isNotNull();
        assertThat(created.keycloakSub()).isEqualTo(identity.subject());
        assertThat(created.merchantProfile()).isNull();
    }

    @Test
    void repeatedCreationReturnsSameIdentityWithoutOverwritingBusinessAttributes() {
        var identity = identity();
        var first = service.getOrCreate(identity);
        var later = service.getOrCreate(new TrustedIdentity(identity.subject(), "changed@example.test",
                "Changed claim", List.of("MERCHANT")));
        assertThat(later.id()).isEqualTo(first.id());
        assertThat(later.createdAt()).isEqualTo(first.createdAt());
        assertThat(later.email()).isEqualTo(first.email());
        assertThat(later.roles()).containsExactly("MERCHANT");
        assertThat(later.merchantProfile()).isNull();
        assertThat(count(identity.subject())).isEqualTo(1);
    }

    @Test
    void targetedInsertConflictReloadsOriginalWithoutChangingAttributes() {
        var identity = identity();
        var first = service.getOrCreate(identity);
        var rows = new TransactionTemplate(transactionManager).execute(status ->
                repository.insertIfAbsent(identity.subject(), "other@example.test", "Other name"));
        assertThat(rows).isZero();
        assertThat(service.getOrCreate(identity)).isEqualTo(first);
    }

    @Test
    void differentSubjectsCannotReadOrReassignEachOthersProfile() {
        var a = identity();
        var b = identity();
        var profileA = service.getOrCreate(a);
        var profileB = service.getOrCreate(b);
        assertThat(profileA.id()).isNotEqualTo(profileB.id());
        assertThat(service.getOrCreate(a)).isEqualTo(profileA);
        assertThat(service.getOrCreate(b)).isEqualTo(profileB);
        assertThat(repository.findByKeycloakSub("unknown-" + UUID.randomUUID())).isEmpty();
    }

    @Test
    void optionalIdentityAttributesUseEmptyStringsAndNoInventedRole() {
        var profile = service.getOrCreate(new TrustedIdentity("missing-" + UUID.randomUUID(), null, null, List.of()));
        assertThat(profile.email()).isEmpty();
        assertThat(profile.fullName()).isEmpty();
        assertThat(profile.phoneNumber()).isEmpty();
        assertThat(profile.roles()).isEmpty();
    }

    @Test
    void databaseEnforcesUniqueSubjectEvenOutsideRepository() {
        var identity = identity();
        var profile = service.getOrCreate(identity);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO account_profiles (id, keycloak_sub, email, full_name, phone_number, created_at)
                SELECT gen_random_uuid(), keycloak_sub, email, full_name, phone_number, created_at
                FROM account_profiles WHERE id = ?
                """, profile.id())).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        assertThat(count(identity.subject())).isEqualTo(1);
    }

    @Test
    void databaseRejectsBlankSubject() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO account_profiles VALUES (gen_random_uuid(), '   ', '', '', '', CURRENT_TIMESTAMP)
                """)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void databaseRejectsChangingSubjectOrProfileId() {
        var profile = service.getOrCreate(identity());
        assertThatThrownBy(() -> jdbc.update("UPDATE account_profiles SET keycloak_sub = ? WHERE id = ?",
                "other-subject", profile.id())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE account_profiles SET id = ? WHERE id = ?",
                UUID.randomUUID(), profile.id())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(repository.findByKeycloakSub(profile.keycloakSub()).orElseThrow().getId()).isEqualTo(profile.id());
    }

    @Test
    void schemaContainsOnlyBusinessProfileFieldsAndFlywayHistory() {
        var columns = jdbc.queryForList("""
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'account_profiles'
                """, String.class);
        assertThat(columns).containsExactlyInAnyOrder("id", "keycloak_sub", "email", "full_name", "phone_number", "created_at");
        assertThat(jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'
                """, String.class)).containsExactlyInAnyOrder("account_profiles", "merchants", "staff_profiles", "flyway_schema_history");
        assertThat(AccountProfile.class.getMethods()).noneMatch(method -> method.getName().startsWith("set"));
    }

    @Test
    void concurrentFirstRequestsReturnOnePersistedProfile() throws Exception {
        var identity = identity();
        var ready = new CountDownLatch(8);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(8)) {
            var futures = IntStream.range(0, 8).mapToObj(i -> pool.submit(() -> {
                ready.countDown();
                if (!start.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Start timeout");
                return service.getOrCreate(identity);
            })).toList();
            assertThat(ready.await(15, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var first = futures.getFirst().get(30, TimeUnit.SECONDS);
            for (var future : futures) assertThat(future.get(30, TimeUnit.SECONDS)).isEqualTo(first);
        }
        assertThat(count(identity.subject())).isEqualTo(1);
    }

    private int count(String subject) {
        return jdbc.queryForObject("SELECT count(*) FROM account_profiles WHERE keycloak_sub = ?", Integer.class, subject);
    }
}
