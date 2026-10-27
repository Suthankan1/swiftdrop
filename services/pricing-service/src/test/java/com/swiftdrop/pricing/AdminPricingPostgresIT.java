package com.swiftdrop.pricing;

import com.swiftdrop.pricing.api.dto.AdminPricingDtos.*;
import com.swiftdrop.pricing.api.dto.QuoteRequest;
import com.swiftdrop.pricing.application.AdminPricingService;
import com.swiftdrop.pricing.application.QuoteApplicationService;
import com.swiftdrop.pricing.domain.SizeCategory;
import com.swiftdrop.pricing.error.AdminConfigurationException;
import com.swiftdrop.pricing.persistence.*;
import com.swiftdrop.pricing.security.CallerContext;
import com.swiftdrop.pricing.security.SecurityContextCallerResolver;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = "grpc.server.enabled=false")
class AdminPricingPostgresIT {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("pricing_admin_test");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
    @Autowired AdminPricingService admin;
    @Autowired QuoteApplicationService quotes;
    @Autowired JdbcTemplate jdbc;
    @Autowired Flyway flyway;
    @Autowired com.swiftdrop.pricing.grpc.PricingInternalServiceImpl quoteVerifier;
    @Autowired PricingConfigurationLock configurationLock;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean SecurityContextCallerResolver caller;
    private UUID active;

    @BeforeEach
    void fixtures() {
        when(caller.resolveCurrentCaller()).thenReturn(CallerContext.authenticated("admin-test", null, Set.of("ADMIN")));
        // Only this disposable Testcontainers database is reset; never a developer/platform database.
        jdbc.execute("TRUNCATE pricing_quotes, pricing_admin_audit, pricing_rate_rules, pricing_rate_versions, pricing_zones CASCADE");
        jdbc.update("INSERT INTO pricing_zones(zone_code,name,description,standard_transit_hours) VALUES ('ZONE_1','One','',24)");
        DraftCreate draft = new DraftCreate("RATE-V1", "LKR", Instant.now().minusSeconds(3600), null, "V1");
        VersionView version = admin.createDraft(draft);
        version = fill(version, 35000);
        active = admin.activate(version.id(), new Activate(version.revision(), null)).id();
    }

    @Test
    void flywayAndZoneCrudAuditAndSoftDisable() {
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
        assertThat(flyway.info().applied()).hasSize(4);
        ZoneView zone = admin.createZone(new ZoneCreate("ZONE_2", "Two", "Description", 48));
        assertThat(admin.getZone(zone.id()).zoneCode()).isEqualTo("ZONE_2");
        assertThat(admin.listZones(0, 20).items()).hasSize(2);
        ZoneView updated = admin.updateZone(zone.id(), new ZoneUpdate("Updated", "New", 72, zone.revision()));
        assertThat(updated.revision()).isEqualTo(zone.revision() + 1);
        ZoneView disabled = admin.disableZone(zone.id(), new ExpectedRevision(updated.revision()));
        assertThat(disabled.active()).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pricing_zones WHERE id = ?", Integer.class, zone.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pricing_admin_audit WHERE resource_id = ? AND actor_sub = 'admin-test'", Integer.class, zone.id())).isEqualTo(3);
        assertThatThrownBy(() -> admin.createZone(new ZoneCreate("ZONE_2", "Duplicate", "", 24))).isInstanceOf(AdminConfigurationException.class);
    }

    @Test
    void staleRevisionAndActiveRuleReferencesAreRejected() {
        ZoneView zone = admin.listZones(0, 20).items().getFirst();
        assertThatThrownBy(() -> admin.disableZone(zone.id(), new ExpectedRevision(zone.revision())))
                .isInstanceOfSatisfying(AdminConfigurationException.class, ex -> assertThat(ex.getErrorCode()).isEqualTo("ZONE_REFERENCED"));
        admin.updateZone(zone.id(), new ZoneUpdate("Updated", "", 24, zone.revision()));
        assertThatThrownBy(() -> admin.updateZone(zone.id(), new ZoneUpdate("Lost update", "", 24, zone.revision())))
                .isInstanceOfSatisfying(AdminConfigurationException.class, ex -> assertThat(ex.getStatus()).isEqualTo(409));
    }

    @Test
    void publishRetainsOldQuoteAndRulesAndNewQuotesUseNewCard() {
        CallerContext customer = CallerContext.authenticated("customer-test", null, Set.of("CUSTOMER"));
        QuoteRequest input = new QuoteRequest("ZONE_1", "ZONE_1", BigDecimal.ONE, 10, 10, 10);
        var old = quotes.createQuote(input, customer);
        VersionView draft = fill(admin.createDraft(new DraftCreate("RATE-V2", "LKR", Instant.now().minusSeconds(1), null, "V2")), 45000);
        VersionView published = admin.activate(draft.id(), new Activate(draft.revision(), active));
        var next = quotes.createQuote(input, customer);
        assertThat(published.status().name()).isEqualTo("ACTIVE");
        assertThat(next.rateVersion()).isEqualTo("RATE-V2");
        assertThat(next.totalAmountMinorUnits()).isEqualTo(45000);
        assertThat(quotes.getQuote(old.quoteId(), customer)).isEqualTo(old);
        assertThat(old.totalAmountMinorUnits()).isEqualTo(35000);
        assertThat(admin.listRules(active, 0, 20).items()).hasSize(4);
        assertThat(jdbc.queryForObject("SELECT status FROM pricing_rate_versions WHERE id = ?", String.class, active)).isEqualTo("SUPERSEDED");
        VersionView oldVersion = admin.listVersions(0, 20).items().stream().filter(v -> v.id().equals(active)).findFirst().orElseThrow();
        admin.archive(active, new ExpectedRevision(oldVersion.revision()));
        assertThat(quotes.getQuote(old.quoteId(), customer)).isEqualTo(old);
        var actor = new com.swiftdrop.security.grpc.DelegatedActorContext("customer-test", Set.of("CUSTOMER"),
                com.swiftdrop.security.service.SwiftDropServiceIdentity.DELIVERY_SERVICE);
        var response = new java.util.concurrent.atomic.AtomicReference<com.swiftdrop.pricing.v1.ValidateQuoteResponse>();
        io.grpc.Context.current().withValue(com.swiftdrop.security.grpc.SwiftDropGrpcContext.KEY_DELEGATED_ACTOR, actor)
                .run(() -> quoteVerifier.validateQuote(com.swiftdrop.pricing.v1.ValidateQuoteRequest.newBuilder()
                        .setQuoteId(old.quoteId().toString()).setExpectedRateVersion(old.rateVersion())
                        .setExpectedCurrency(old.currency()).setExpectedAmountMinorUnits(old.totalAmountMinorUnits()).build(),
                        new io.grpc.stub.StreamObserver<>() {
                            public void onNext(com.swiftdrop.pricing.v1.ValidateQuoteResponse value) { response.set(value); }
                            public void onError(Throwable error) { throw new AssertionError(error); }
                            public void onCompleted() { }
                        }));
        assertThat(response.get().getIsValid()).isTrue();
        assertThat(response.get().getVerifiedAmountMinorUnits()).isEqualTo(old.totalAmountMinorUnits());
    }

    @Test
    void databaseGuardsRejectPublishedMutationAndOverlappingDraftBands() {
        assertThatThrownBy(() -> jdbc.update("UPDATE pricing_rate_rules SET base_fee_minor_units = 1 WHERE rate_version_id = ?", active))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        VersionView draft = admin.createDraft(new DraftCreate("RATE-DRAFT", "LKR", Instant.now(), null, ""));
        RuleView first = admin.writeRule(draft.id(), null, rule(draft.revision(), SizeCategory.MEDIUM, 100));
        assertThat(first.revision()).isGreaterThan(draft.revision());
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO pricing_rate_rules(rate_version_id,pickup_zone_code,dropoff_zone_code,size_category,
                min_weight_kg,max_weight_kg,base_fee_minor_units,per_kg_surcharge_minor_units,weight_threshold_kg)
                VALUES (?, 'ZONE_1','ZONE_1','MEDIUM',1.00,2.00,1,0,0)
                """, draft.id())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        RuleView changed = admin.writeRule(draft.id(), first.id(), rule(first.revision(), SizeCategory.MEDIUM, 200));
        assertThat(changed.id()).isEqualTo(first.id());
        assertThat(changed.baseFeeMinorUnits()).isEqualTo(200);
    }

    @Test
    void concurrentPublishUsesActiveCardComparisonAndKeepsExactlyOneActive() throws Exception {
        VersionView a = fill(admin.createDraft(new DraftCreate("RATE-A", "LKR", Instant.now().minusSeconds(1), null, "")), 100);
        VersionView b = fill(admin.createDraft(new DraftCreate("RATE-B", "LKR", Instant.now().minusSeconds(1), null, "")), 200);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Integer> first = executor.submit(() -> publishResult(a, start));
            Future<Integer> second = executor.submit(() -> publishResult(b, start));
            start.countDown();
            assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS))).containsExactlyInAnyOrder(200, 409);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pricing_rate_versions WHERE status = 'ACTIVE' AND currency = 'LKR'", Integer.class)).isEqualTo(1);
    }

    @Test
    void concurrentDraftRuleUpdatesRejectLostUpdateAndAuditOnlyWinner() throws Exception {
        VersionView draft = admin.createDraft(new DraftCreate("RATE-EDIT", "LKR", Instant.now(), null, ""));
        RuleView original = admin.writeRule(draft.id(), null, rule(draft.revision(), SizeCategory.MEDIUM, 100));
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            java.util.function.LongFunction<Callable<Integer>> edit = fee -> () -> {
                start.await();
                try {
                    admin.writeRule(draft.id(), original.id(), rule(original.revision(), SizeCategory.MEDIUM, fee));
                    return 200;
                } catch (AdminConfigurationException ex) { return ex.getStatus(); }
            };
            Future<Integer> first = executor.submit(edit.apply(200));
            Future<Integer> second = executor.submit(edit.apply(300));
            start.countDown();
            assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        }
        RuleView persisted = admin.listRules(draft.id(), 0, 20).items().getFirst();
        assertThat(persisted.baseFeeMinorUnits()).isIn(200L, 300L);
        assertThat(persisted.revision()).isEqualTo(original.revision() + 1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pricing_admin_audit WHERE resource_id = ? AND operation = 'RATE_RULE_UPDATED'",
                Integer.class, draft.id())).isEqualTo(1);
    }

    @Test
    void sharedQuoteLockBlocksConfigurationUntilQuoteTransactionEnds() throws Exception {
        ZoneView zone = admin.createZone(new ZoneCreate("ZONE_2", "Two", "", 24));
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> reader = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                configurationLock.forQuote(); held.countDown();
                try { assertThat(release.await(10, TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
            }));
            assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
            Future<?> writer = executor.submit(() -> admin.disableZone(zone.id(), new ExpectedRevision(zone.revision())));
            assertThatThrownBy(() -> writer.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown(); reader.get(10, TimeUnit.SECONDS); writer.get(10, TimeUnit.SECONDS);
        } finally { release.countDown(); }
        assertThat(admin.getZone(zone.id()).active()).isFalse();
    }

    @Test
    void auditFailureRollsBackSuccessfulMutation() {
        ZoneView zone = admin.createZone(new ZoneCreate("ZONE_2", "Original", "", 24));
        jdbc.execute("ALTER TABLE pricing_admin_audit ADD CONSTRAINT test_reject_audit CHECK (operation <> 'ZONE_UPDATED')");
        try {
            assertThatThrownBy(() -> admin.updateZone(zone.id(), new ZoneUpdate("Changed", "", 24, zone.revision())))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(admin.getZone(zone.id()).name()).isEqualTo("Original");
            assertThat(admin.getZone(zone.id()).revision()).isEqualTo(zone.revision());
        } finally { jdbc.execute("ALTER TABLE pricing_admin_audit DROP CONSTRAINT test_reject_audit"); }
    }

    private int publishResult(VersionView version, CountDownLatch start) throws InterruptedException {
        start.await();
        try { admin.activate(version.id(), new Activate(version.revision(), active)); return 200; }
        catch (AdminConfigurationException ex) { return ex.getStatus(); }
    }
    private VersionView fill(VersionView draft, long base) {
        long revision = draft.revision();
        for (SizeCategory size : SizeCategory.values()) revision = admin.writeRule(draft.id(), null, rule(revision, size, base)).revision();
        long finalRevision = revision;
        return admin.listVersions(0, 50).items().stream().filter(v -> v.id().equals(draft.id()) && v.revision() == finalRevision).findFirst().orElseThrow();
    }
    private RuleWrite rule(long revision, SizeCategory size, long base) {
        return new RuleWrite(revision, "ZONE_1", "ZONE_1", size, new BigDecimal("0.01"), size.getMaxWeightKg(), base, 0L, BigDecimal.ZERO);
    }
}
