package com.swiftdrop.pricing;

import com.swiftdrop.pricing.domain.RateVersionStatus;
import com.swiftdrop.pricing.domain.SizeCategory;
import com.swiftdrop.pricing.persistence.RateRuleEntity;
import com.swiftdrop.pricing.persistence.RateRuleRepository;
import com.swiftdrop.pricing.persistence.RateVersionEntity;
import com.swiftdrop.pricing.persistence.RateVersionRepository;
import com.swiftdrop.pricing.persistence.ZoneEntity;
import com.swiftdrop.pricing.persistence.ZoneRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.swiftdrop.pricing.v1.PricingAdminSummaryRequest;
import com.swiftdrop.pricing.v1.PricingAdminSummaryResponse;
import com.swiftdrop.pricing.v1.ValidateQuoteRequest;
import com.swiftdrop.pricing.v1.ValidateQuoteResponse;
import com.swiftdrop.security.grpc.DelegatedActorContext;
import com.swiftdrop.security.grpc.SwiftDropGrpcContext;
import com.swiftdrop.security.jwt.SwiftDropRoles;
import com.swiftdrop.security.service.SwiftDropServiceIdentity;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PricingPersistencePostgresIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("pricing_db")
            .withUsername("pricing_app")
            .withPassword("pricing_dev_password");

    @DynamicPropertySource
    static void configureDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ZoneRepository zoneRepository;

    @Autowired
    private RateVersionRepository rateVersionRepository;

    @Autowired
    private RateRuleRepository rateRuleRepository;

    @Autowired
    private com.swiftdrop.pricing.persistence.QuoteRepository quoteRepository;

    @Autowired
    private com.swiftdrop.pricing.grpc.PricingInternalServiceImpl pricingInternalService;

    @Test
    @DisplayName("Should successfully apply Flyway migrations and seed baseline data")
    void shouldApplyMigrationsSuccessfully() {
        assertThat(flyway.info().applied()).hasSizeGreaterThanOrEqualTo(3);

        // Check seeded zones
        List<ZoneEntity> activeZones = zoneRepository.findByActiveTrueOrderByZoneCodeAsc();
        assertThat(activeZones).extracting(ZoneEntity::getZoneCode)
                .contains("ZONE_1", "ZONE_2", "ZONE_3");

        // Check seeded active rate version
        Optional<RateVersionEntity> activeRate = rateVersionRepository.findFirstByCurrencyAndStatus("LKR", RateVersionStatus.ACTIVE);
        assertThat(activeRate).isPresent();
        assertThat(activeRate.get().getVersionCode()).isEqualTo("RATE-2026-V1");

        // Check seeded rate rules
        List<RateRuleEntity> rules = rateRuleRepository.findByRateVersionId(activeRate.get().getId());
        assertThat(rules).isNotEmpty();
    }

    @Test
    @DisplayName("Should enforce unique zone_code constraint")
    void shouldEnforceUniqueZoneCode() {
        ZoneEntity duplicate = new ZoneEntity("ZONE_1", "Duplicate Colombo", "Desc", 24, true);

        assertThatThrownBy(() -> zoneRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Should enforce unique version_code constraint")
    void shouldEnforceUniqueVersionCode() {
        RateVersionEntity duplicate = new RateVersionEntity(
                "RATE-2026-V1", "LKR", RateVersionStatus.DRAFT, Instant.now(), "Duplicate");

        assertThatThrownBy(() -> rateVersionRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Should enforce at most one ACTIVE rate version per currency via partial unique index")
    void shouldEnforceSingleActiveRateVersionPerCurrency() {
        RateVersionEntity secondActive = new RateVersionEntity(
                "RATE-2026-V2-CONFLICT", "LKR", RateVersionStatus.ACTIVE, Instant.now(), "Second active");

        assertThatThrownBy(() -> rateVersionRepository.saveAndFlush(secondActive))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Should allow multiple historical or draft rate versions to coexist")
    void shouldAllowMultipleCoexistingVersions() {
        RateVersionEntity draft = new RateVersionEntity(
                "RATE-2027-DRAFT", "LKR", RateVersionStatus.DRAFT, Instant.now(), "Future draft");
        RateVersionEntity savedDraft = rateVersionRepository.saveAndFlush(draft);

        assertThat(savedDraft.getId()).isNotNull();
        assertThat(savedDraft.getStatus()).isEqualTo(RateVersionStatus.DRAFT);
    }

    @Test
    @DisplayName("Should enforce foreign key constraints on rate rules")
    void shouldEnforceForeignKeyConstraints() {
        // Unknown rate_version_id
        RateRuleEntity invalidRule = new RateRuleEntity(
                UUID.randomUUID(), "ZONE_1", "ZONE_1", SizeCategory.SMALL,
                new BigDecimal("0.01"), new BigDecimal("1.00"),
                20000L, 0L, BigDecimal.ZERO
        );

        assertThatThrownBy(() -> rateRuleRepository.saveAndFlush(invalidRule))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Should enforce weight boundary check constraints in PostgreSQL")
    void shouldEnforceWeightBoundaryCheckConstraints() {
        RateVersionEntity active = rateVersionRepository.findByVersionCode("RATE-2026-V1").orElseThrow();

        // min_weight_kg < 0.01 violated
        assertThatThrownBy(() -> jdbc.execute(
                "INSERT INTO pricing_rate_rules (rate_version_id, pickup_zone_code, dropoff_zone_code, " +
                        "size_category, min_weight_kg, max_weight_kg, base_fee_minor_units) " +
                        "VALUES ('" + active.getId() + "', 'ZONE_1', 'ZONE_1', 'SMALL', 0.00, 1.00, 1000)"))
                .isInstanceOf(DataIntegrityViolationException.class);

        // max_weight_kg > 30.00 violated
        assertThatThrownBy(() -> jdbc.execute(
                "INSERT INTO pricing_rate_rules (rate_version_id, pickup_zone_code, dropoff_zone_code, " +
                        "size_category, min_weight_kg, max_weight_kg, base_fee_minor_units) " +
                        "VALUES ('" + active.getId() + "', 'ZONE_1', 'ZONE_1', 'SMALL', 1.00, 35.00, 1000)"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Should successfully persist and retrieve immutable quote snapshot in PostgreSQL")
    void shouldPersistAndRetrieveQuoteSnapshot() {
        RateVersionEntity active = rateVersionRepository.findByVersionCode("RATE-2026-V1").orElseThrow();
        UUID quoteId = UUID.randomUUID();
        Instant now = Instant.now();
        Instant expires = now.plusSeconds(900);

        com.swiftdrop.pricing.persistence.QuoteEntity quote = new com.swiftdrop.pricing.persistence.QuoteEntity(
                quoteId,
                active.getId(),
                active.getVersionCode(),
                null,
                "ZONE_1",
                "ZONE_2",
                30,
                20,
                15,
                new BigDecimal("2.50"),
                new BigDecimal("1.80"),
                new BigDecimal("2.50"),
                SizeCategory.MEDIUM,
                35000L,
                15000L,
                50000L,
                "LKR",
                "cust-sub-123",
                "tenant-xyz",
                false,
                now,
                expires
        );

        quoteRepository.saveAndFlush(quote);

        Optional<com.swiftdrop.pricing.persistence.QuoteEntity> retrieved = quoteRepository.findById(quoteId);
        assertThat(retrieved).isPresent();
        com.swiftdrop.pricing.persistence.QuoteEntity entity = retrieved.get();
        assertThat(entity.getRateVersionCode()).isEqualTo("RATE-2026-V1");
        assertThat(entity.getTotalAmountMinorUnits()).isEqualTo(50000L);
        assertThat(entity.getSubjectSub()).isEqualTo("cust-sub-123");
        assertThat(entity.getTenantId()).isEqualTo("tenant-xyz");
        assertThat(entity.isAnonymous()).isFalse();
    }

    @Test
    @DisplayName("Should enforce PostgreSQL check constraints on pricing_quotes table")
    void shouldEnforceQuoteCheckConstraints() {
        RateVersionEntity active = rateVersionRepository.findByVersionCode("RATE-2026-V1").orElseThrow();
        UUID quoteId = UUID.randomUUID();
        Instant now = Instant.now();

        // expires_at <= created_at violated
        assertThatThrownBy(() -> jdbc.execute(
                "INSERT INTO pricing_quotes (id, rate_version_id, rate_version_code, pickup_zone_code, dropoff_zone_code, " +
                        "length_cm, width_cm, height_cm, physical_weight_kg, volumetric_weight_kg, chargeable_weight_kg, " +
                        "size_category, base_fee_minor_units, weight_surcharge_minor_units, total_amount_minor_units, " +
                        "currency, is_anonymous, created_at, expires_at) VALUES (" +
                        "'" + quoteId + "', '" + active.getId() + "', 'RATE-2026-V1', 'ZONE_1', 'ZONE_2', " +
                        "30, 20, 15, 2.50, 1.80, 2.50, 'MEDIUM', 35000, 15000, 50000, 'LKR', true, " +
                        "'" + now + "', '" + now + "')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("gRPC Provider in PostgreSQL: ValidateQuote returns persisted immutable snapshot with zero database mutations")
    void shouldValidateQuoteAgainstPostgresWithoutMutations() {
        RateVersionEntity active = rateVersionRepository.findByVersionCode("RATE-2026-V1").orElseThrow();
        UUID quoteId = UUID.randomUUID();
        Instant now = Instant.now();
        Instant expires = now.plusSeconds(900);
        String customerSub = "postgres-cust-1";

        com.swiftdrop.pricing.persistence.QuoteEntity quote = new com.swiftdrop.pricing.persistence.QuoteEntity(
                quoteId,
                active.getId(),
                active.getVersionCode(),
                null,
                "ZONE_1",
                "ZONE_2",
                25,
                15,
                10,
                new BigDecimal("1.20"),
                new BigDecimal("0.75"),
                new BigDecimal("1.20"),
                SizeCategory.SMALL,
                20000L,
                5000L,
                25000L,
                "LKR",
                customerSub,
                null,
                false,
                now,
                expires
        );
        quoteRepository.saveAndFlush(quote);

        long countBefore = quoteRepository.count();

        // Execute ValidateQuote through PricingInternalServiceImpl with valid customer delegation
        DelegatedActorContext actor = new DelegatedActorContext(
                customerSub,
                Set.of(SwiftDropRoles.ROLE_CUSTOMER),
                SwiftDropServiceIdentity.DELIVERY_SERVICE
        );

        TestStreamObserver<ValidateQuoteResponse> observer = new TestStreamObserver<>();
        Context context = Context.current()
                .withValue(SwiftDropGrpcContext.KEY_CALLING_SERVICE, SwiftDropServiceIdentity.DELIVERY_SERVICE)
                .withValue(SwiftDropGrpcContext.KEY_DELEGATED_ACTOR, actor);

        context.run(() -> pricingInternalService.validateQuote(
                ValidateQuoteRequest.newBuilder()
                        .setQuoteId(quoteId.toString())
                        .setExpectedRateVersion("RATE-2026-V1")
                        .setExpectedAmountMinorUnits(25000L)
                        .setExpectedCurrency("LKR")
                        .build(),
                observer
        ));

        assertThat(observer.error).isNull();
        assertThat(observer.completed).isTrue();
        assertThat(observer.value).isNotNull();

        ValidateQuoteResponse response = observer.value;
        assertThat(response.getIsValid()).isTrue();
        assertThat(response.getValidationCode()).isEqualTo("VALID");
        assertThat(response.getQuoteId()).isEqualTo(quoteId.toString());
        assertThat(response.getRateVersion()).isEqualTo("RATE-2026-V1");
        assertThat(response.getVerifiedAmountMinorUnits()).isEqualTo(25000L);
        assertThat(response.getCurrency()).isEqualTo("LKR");
        assertThat(response.getPickupZone()).isEqualTo("ZONE_1");
        assertThat(response.getDropoffZone()).isEqualTo("ZONE_2");
        assertThat(response.getSubjectSub()).isEqualTo(customerSub);
        assertThat(response.getBaseFeeMinorUnits()).isEqualTo(20000L);
        assertThat(response.getWeightSurchargeMinorUnits()).isEqualTo(5000L);

        // Verification of zero database mutations
        long countAfter = quoteRepository.count();
        assertThat(countAfter).isEqualTo(countBefore);
    }

    @Test
    @DisplayName("gRPC Provider in PostgreSQL: Quote issued under older tariff remains valid after new rate version is added")
    void quoteRemainsValidAfterNewRateVersionAdded() {
        RateVersionEntity active = rateVersionRepository.findByVersionCode("RATE-2026-V1").orElseThrow();
        UUID quoteId = UUID.randomUUID();
        Instant now = Instant.now();
        Instant expires = now.plusSeconds(900);
        String customerSub = "postgres-cust-older";

        com.swiftdrop.pricing.persistence.QuoteEntity quote = new com.swiftdrop.pricing.persistence.QuoteEntity(
                quoteId,
                active.getId(),
                active.getVersionCode(),
                null,
                "ZONE_1",
                "ZONE_3",
                40,
                30,
                20,
                new BigDecimal("5.00"),
                new BigDecimal("4.80"),
                new BigDecimal("5.00"),
                SizeCategory.LARGE,
                50000L,
                25000L,
                75000L,
                "LKR",
                customerSub,
                null,
                false,
                now,
                expires
        );
        quoteRepository.saveAndFlush(quote);

        // Simulate admin creating another coexisting rate version (DRAFT/ARCHIVED)
        RateVersionEntity newerVersion = new RateVersionEntity(
                "RATE-2027-NEW", "LKR", RateVersionStatus.DRAFT, now, "Newer Rate"
        );
        rateVersionRepository.saveAndFlush(newerVersion);

        // Older quote remains valid when evaluated
        DelegatedActorContext actor = new DelegatedActorContext(
                customerSub,
                Set.of(SwiftDropRoles.ROLE_CUSTOMER),
                SwiftDropServiceIdentity.DELIVERY_SERVICE
        );

        TestStreamObserver<ValidateQuoteResponse> observer = new TestStreamObserver<>();
        Context context = Context.current()
                .withValue(SwiftDropGrpcContext.KEY_CALLING_SERVICE, SwiftDropServiceIdentity.DELIVERY_SERVICE)
                .withValue(SwiftDropGrpcContext.KEY_DELEGATED_ACTOR, actor);

        context.run(() -> pricingInternalService.validateQuote(
                ValidateQuoteRequest.newBuilder()
                        .setQuoteId(quoteId.toString())
                        .setExpectedRateVersion("RATE-2026-V1")
                        .setExpectedAmountMinorUnits(75000L)
                        .setExpectedCurrency("LKR")
                        .build(),
                observer
        ));

        assertThat(observer.error).isNull();
        assertThat(observer.value.getIsValid()).isTrue();
        assertThat(observer.value.getValidationCode()).isEqualTo("VALID");
        assertThat(observer.value.getRateVersion()).isEqualTo("RATE-2026-V1");
    }

    @Test
    @DisplayName("gRPC Provider in PostgreSQL: Admin summary query returns real zones and rate versions from pricing_db")
    void adminSummaryQuery_returnsRealPostgresData() {
        DelegatedActorContext adminActor = new DelegatedActorContext(
                "admin-sub-pg",
                Set.of(SwiftDropRoles.ROLE_ADMIN),
                SwiftDropServiceIdentity.GRAPHQL_QUERY_SERVICE
        );

        TestStreamObserver<PricingAdminSummaryResponse> observer = new TestStreamObserver<>();
        Context context = Context.current()
                .withValue(SwiftDropGrpcContext.KEY_CALLING_SERVICE, SwiftDropServiceIdentity.GRAPHQL_QUERY_SERVICE)
                .withValue(SwiftDropGrpcContext.KEY_DELEGATED_ACTOR, adminActor);

        context.run(() -> pricingInternalService.getPricingAdminSummary(
                PricingAdminSummaryRequest.newBuilder()
                        .setActiveOnly(true)
                        .setPage(0)
                        .setPageSize(20)
                        .build(),
                observer
        ));

        assertThat(observer.error).isNull();
        assertThat(observer.value).isNotNull();
        PricingAdminSummaryResponse summary = observer.value;
        assertThat(summary.getTotalZones()).isGreaterThanOrEqualTo(3);
        assertThat(summary.getZonesList()).extracting(com.swiftdrop.pricing.v1.PricingZoneSummaryItem::getZoneCode)
                .contains("ZONE_1", "ZONE_2", "ZONE_3");
        assertThat(summary.getTotalRateVersions()).isGreaterThanOrEqualTo(1);
    }

    static class TestStreamObserver<T> implements StreamObserver<T> {
        T value;
        Throwable error;
        boolean completed = false;

        @Override
        public void onNext(T value) {
            this.value = value;
        }

        @Override
        public void onError(Throwable t) {
            this.error = t;
        }

        @Override
        public void onCompleted() {
            this.completed = true;
        }
    }
}
