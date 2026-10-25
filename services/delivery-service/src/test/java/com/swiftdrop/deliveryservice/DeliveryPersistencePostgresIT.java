package com.swiftdrop.deliveryservice;

import com.swiftdrop.deliveryservice.core.Parcel;
import com.swiftdrop.deliveryservice.core.ParcelRepository;
import com.swiftdrop.deliveryservice.core.ParcelStatus;
import com.swiftdrop.deliveryservice.idempotency.IdempotencyRecord;
import com.swiftdrop.deliveryservice.idempotency.IdempotencyRecordRepository;
import com.swiftdrop.deliveryservice.idempotency.IdempotencyStatus;
import com.swiftdrop.deliveryservice.status.StatusHistory;
import com.swiftdrop.deliveryservice.status.StatusHistoryRepository;
import org.flywaydb.core.Flyway;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DeliveryPersistencePostgresIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("delivery_db")
            .withUsername("delivery_app")
            .withPassword("delivery_secret");

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
    private ParcelRepository parcelRepository;

    @Autowired
    private StatusHistoryRepository statusHistoryRepository;

    @Autowired
    private IdempotencyRecordRepository idempotencyRecordRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("Verify clean Flyway V1 migration execution on real PostgreSQL")
    void testFlywayMigrationExecution() {
        var info = flyway.info();
        assertThat(info.applied()).hasSize(2);
        assertThat(info.applied()[0].getDescription()).isEqualTo("create delivery schema");
        assertThat(info.applied()[0].getVersion().getVersion()).isEqualTo("1");
        assertThat(info.applied()[1].getDescription()).isEqualTo("create delivery assignments schema");
        assertThat(info.applied()[1].getVersion().getVersion()).isEqualTo("2");

        // Verify tables exist in public schema
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' ORDER BY table_name;",
                String.class
        );
        assertThat(tables).contains("flyway_schema_history", "idempotency_records", "parcels", "status_history", "delivery_assignments");
    }

    @Test
    @DisplayName("Persist Parcel and StatusHistory atomically within single transaction")
    void testPersistParcelAndStatusHistory() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        UUID parcelId = UUID.randomUUID();
        UUID historyId = UUID.randomUUID();
        String trackingNumber = "SWD-2026-IT-" + UUID.randomUUID().toString().substring(0, 8);

        tx.executeWithoutResult(status -> {
            Parcel parcel = new Parcel(
                    parcelId,
                    trackingNumber,
                    "customer-sub-100",
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "2026.1",
                    125000L,
                    "LKR",
                    new BigDecimal("1.500")
            );
            parcelRepository.save(parcel);

            StatusHistory history = new StatusHistory(
                    historyId,
                    parcelId,
                    null,
                    ParcelStatus.CREATED,
                    null,
                    "Initial parcel booking",
                    null,
                    "customer-sub-100",
                    1L,
                    Instant.now()
            );
            statusHistoryRepository.save(history);
        });

        // Verify persisted records
        Parcel loadedParcel = parcelRepository.findById(parcelId).orElseThrow();
        assertThat(loadedParcel.getTrackingNumber()).isEqualTo(trackingNumber);
        assertThat(loadedParcel.getStatus()).isEqualTo(ParcelStatus.CREATED);
        assertThat(loadedParcel.getVersion()).isEqualTo(1L);

        List<StatusHistory> historyList = statusHistoryRepository.findByParcelIdOrderByResultingVersionAsc(parcelId);
        assertThat(historyList).hasSize(1);
        assertThat(historyList.get(0).getNewStatus()).isEqualTo(ParcelStatus.CREATED);
        assertThat(historyList.get(0).getResultingVersion()).isEqualTo(1L);
    }

    @Test
    @DisplayName("Enforce unique tracking number constraint on parcels table")
    void testUniqueTrackingNumberConstraint() {
        String trackingNumber = "SWD-DUPLICATE-TRACKING";

        Parcel parcel1 = new Parcel(
                UUID.randomUUID(),
                trackingNumber,
                "user-1",
                null, null, "v1", 1000L, "LKR", null
        );
        parcelRepository.saveAndFlush(parcel1);

        Parcel parcel2 = new Parcel(
                UUID.randomUUID(),
                trackingNumber,
                "user-2",
                null, null, "v1", 2000L, "LKR", null
        );

        assertThatThrownBy(() -> parcelRepository.saveAndFlush(parcel2))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Enforce idempotency uniqueness constraint per (principal_or_tenant, idempotency_key)")
    void testIdempotencyUniquenessConstraint() {
        String principal = "merchant-tenant-101";
        String key = "idem-req-999";
        Instant expires = Instant.now().plusSeconds(3600);

        IdempotencyRecord record1 = new IdempotencyRecord(
                UUID.randomUUID(),
                principal,
                key,
                "digest-1",
                IdempotencyStatus.IN_PROGRESS,
                null,
                expires
        );
        idempotencyRecordRepository.saveAndFlush(record1);
        assertThat(idempotencyRecordRepository.existsByPrincipalOrTenantAndIdempotencyKey(principal, key)).isTrue();
        assertThat(idempotencyRecordRepository.findByPrincipalOrTenantAndIdempotencyKey(principal, key))
                .isPresent()
                .hasValueSatisfying(r -> assertThat(r.getRecordId()).isEqualTo(record1.getRecordId()));

        IdempotencyRecord record2 = new IdempotencyRecord(
                UUID.randomUUID(),
                principal,
                key,
                "digest-2",
                IdempotencyStatus.IN_PROGRESS,
                null,
                expires
        );

        assertThatThrownBy(() -> idempotencyRecordRepository.saveAndFlush(record2))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Enforce status check constraint on parcels table")
    void testParcelStatusCheckConstraint() {
        assertThatThrownBy(() -> {
            jdbc.execute(
                    "INSERT INTO parcels (parcel_id, tracking_number, sender_sub, status, version, currency) " +
                    "VALUES ('" + UUID.randomUUID() + "', 'SWD-INVALID-STATUS', 'sub', 'INVALID_STATUS', 1, 'LKR');"
            );
        }).isInstanceOf(DataIntegrityViolationException.class);
    }
}
