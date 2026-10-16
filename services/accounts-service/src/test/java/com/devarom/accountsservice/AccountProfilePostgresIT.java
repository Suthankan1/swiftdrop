package com.devarom.accountsservice;

import com.devarom.accountsservice.identity.TrustedIdentity;
import com.devarom.accountsservice.profile.AccountProfile;
import com.devarom.accountsservice.profile.AccountProfileRepository;
import com.devarom.accountsservice.profile.CurrentUserProfileService;
import jakarta.persistence.EntityManager;
import org.flywaydb.core.Flyway;
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
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("accounts_db");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
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

    private TrustedIdentity identity() {
        return new TrustedIdentity("synthetic-" + UUID.randomUUID(), "customer@example.test",
                "Synthetic Customer", List.of("CUSTOMER"));
    }

    @Test
    void migratesCleanPostgresAndValidatesSchema() {
        assertThat(flyway.info().pending()).isEmpty();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("1");
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
                """, String.class)).containsExactlyInAnyOrder("account_profiles", "flyway_schema_history");
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
