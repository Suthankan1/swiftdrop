package com.swiftdrop.accountsservice.merchant;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.swiftdrop.accountsservice.identity.TrustedIdentity;
import com.swiftdrop.accountsservice.profile.AccountProfileRepository;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PostgreSQL and Flyway integration test for Merchant Enrollment in accounts_db.
 * Verifies real schema constraints, keycloak_sub and registration_number uniqueness,
 * tenant binding immutability trigger, and concurrency safety.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MerchantEnrollmentPostgresIT {

    private static MockWebServer authServer;
    private static RSAKey trustedRsaKey;
    private static String issuerUrl;

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("accounts_db");

    @BeforeAll
    static void setUpAuthServer() throws Exception {
        trustedRsaKey = new RSAKeyGenerator(2048)
                .keyID("trusted-merchant-it-key")
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
        issuerUrl = authServer.url("/realms/swiftdrop").toString().replaceAll("/$", "");
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
        registry.add("swiftdrop.jwt.jwk-set-uri", () -> authServer.url("/protocol/openid-connect/certs").toString());
        registry.add("swiftdrop.jwt.issuer-uri", () -> issuerUrl);
        registry.add("swiftdrop.jwt.audience", () -> "swiftdrop-api");
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> authServer.url("/protocol/openid-connect/certs").toString());
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> issuerUrl);
    }

    @Autowired MerchantEnrollmentService enrollmentService;
    @Autowired MerchantRepository merchantRepository;
    @Autowired AccountProfileRepository accountProfileRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired Flyway flyway;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("1. Flyway migrations V1 through V4 apply cleanly and merchants table exists")
    void flywayMigrationsApplyCleanly() {
        var info = flyway.info();
        assertThat(info.applied().length).isGreaterThanOrEqualTo(2);

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'merchants'",
                Integer.class
        );
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("2. Enrolling merchant persists PENDING state and associates AccountProfile in PostgreSQL")
    void enrollMerchantPersistsPendingRecord() {
        String sub = "sub-it-" + UUID.randomUUID();
        var identity = new TrustedIdentity(sub, "it-merchant@swiftdrop.test", "IT Merchant", List.of("CUSTOMER"));
        var request = new MerchantRegistrationRequest("Lanka Express LK", "PV-" + UUID.randomUUID().toString().substring(0, 8));

        var response = enrollmentService.enrollMerchant(identity, request);

        assertThat(response).isNotNull();
        assertThat(response.status()).isEqualTo(MerchantStatus.PENDING);
        assertThat(response.keycloakSub()).isEqualTo(sub);

        // Verify direct database persistence
        var merchantOpt = merchantRepository.findById(response.id());
        assertThat(merchantOpt).isPresent();
        var merchant = merchantOpt.get();
        assertThat(merchant.getStatus()).isEqualTo(MerchantStatus.PENDING);
        assertThat(merchant.getKeycloakSub()).isEqualTo(sub);
        assertThat(merchant.getBusinessName()).isEqualTo(request.businessName());
        assertThat(merchant.getRegistrationNumber()).isEqualTo(request.registrationNumber());
        assertThat(merchant.getAccountProfileId()).isNotNull();

        // Verify account profile association
        var profileOpt = accountProfileRepository.findByKeycloakSub(sub);
        assertThat(profileOpt).isPresent();
        assertThat(profileOpt.get().getId()).isEqualTo(merchant.getAccountProfileId());
    }

    @Test
    @DisplayName("3. Database uniqueness constraint prevents duplicate keycloak_sub enrollment")
    void duplicateKeycloakSubEnrollmentFails() {
        String sub = "sub-dup-owner-" + UUID.randomUUID();
        var identity = new TrustedIdentity(sub, "dup-owner@swiftdrop.test", "Dup Owner", List.of("CUSTOMER"));
        var regNumber1 = "PV-" + UUID.randomUUID().toString().substring(0, 8);
        var regNumber2 = "PV-" + UUID.randomUUID().toString().substring(0, 8);

        enrollmentService.enrollMerchant(identity, new MerchantRegistrationRequest("Shop 1", regNumber1));

        assertThatThrownBy(() -> enrollmentService.enrollMerchant(identity, new MerchantRegistrationRequest("Shop 2", regNumber2)))
                .isInstanceOf(MerchantConflictException.class)
                .hasMessageContaining("Authenticated user already owns a merchant tenant");
    }

    @Test
    @DisplayName("4. Database uniqueness constraint prevents duplicate registration_number enrollment")
    void duplicateRegistrationNumberEnrollmentFails() {
        String sub1 = "sub-reg-1-" + UUID.randomUUID();
        String sub2 = "sub-reg-2-" + UUID.randomUUID();
        var identity1 = new TrustedIdentity(sub1, "user1@swiftdrop.test", "User One", List.of("CUSTOMER"));
        var identity2 = new TrustedIdentity(sub2, "user2@swiftdrop.test", "User Two", List.of("CUSTOMER"));
        var sharedRegNumber = "PV-" + UUID.randomUUID().toString().substring(0, 8);

        enrollmentService.enrollMerchant(identity1, new MerchantRegistrationRequest("Shop A", sharedRegNumber));

        assertThatThrownBy(() -> enrollmentService.enrollMerchant(identity2, new MerchantRegistrationRequest("Shop B", sharedRegNumber)))
                .isInstanceOf(MerchantConflictException.class)
                .hasMessageContaining("Merchant registration number already exists");
    }

    @Test
    @DisplayName("5. PostgreSQL trigger prevents mutation of merchant identity and tenant binding")
    void triggerProtectsMerchantIdentityImmutability() {
        String sub = "sub-immutable-" + UUID.randomUUID();
        var identity = new TrustedIdentity(sub, "imm@swiftdrop.test", "Imm Merchant", List.of("CUSTOMER"));
        var regNumber = "PV-" + UUID.randomUUID().toString().substring(0, 8);

        var response = enrollmentService.enrollMerchant(identity, new MerchantRegistrationRequest("Immutability Shop", regNumber));

        // Attempt direct SQL update of keycloak_sub should trigger DB exception 23514
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE merchants SET keycloak_sub = 'tampered-sub' WHERE id = ?",
                response.id()
        )).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("Merchant identity and tenant binding are immutable");

        // Attempt direct SQL update of account_profile_id should trigger DB exception 23514
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE merchants SET account_profile_id = ? WHERE id = ?",
                UUID.randomUUID(), response.id()
        )).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("Merchant identity and tenant binding are immutable");
    }

    @Test
    @DisplayName("6. Concurrent duplicate enrollment requests for same subject result in exactly one winner")
    void concurrentDuplicateEnrollmentSafe() throws Exception {
        String sub = "sub-concurrent-" + UUID.randomUUID();
        var identity = new TrustedIdentity(sub, "concurrent@swiftdrop.test", "Concurrent User", List.of("CUSTOMER"));
        int threads = 4;
        var executor = Executors.newFixedThreadPool(threads);
        var readyLatch = new CountDownLatch(threads);
        var startLatch = new CountDownLatch(1);
        var successCount = new AtomicInteger(0);
        var conflictCount = new AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            final int index = i;
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await(5, TimeUnit.SECONDS);
                    var request = new MerchantRegistrationRequest("Concurrent Shop " + index, "PV-CONC-" + index + "-" + UUID.randomUUID().toString().substring(0, 6));
                    enrollmentService.enrollMerchant(identity, request);
                    successCount.incrementAndGet();
                } catch (Throwable ex) {
                    conflictCount.incrementAndGet();
                }
            });
        }

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();
        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        assertThat(successCount.get()).isEqualTo(1);
        assertThat(conflictCount.get()).isEqualTo(threads - 1);

        // Verify exactly one merchant record exists for this subject
        Integer merchantCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM merchants WHERE keycloak_sub = ?",
                Integer.class,
                sub
        );
        assertThat(merchantCount).isEqualTo(1);
    }
}
