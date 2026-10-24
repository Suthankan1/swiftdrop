package com.swiftdrop.accountsservice.staff;

import com.swiftdrop.accountsservice.identity.TrustedIdentity;
import jakarta.persistence.EntityManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StaffProfilePostgresIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("accounts_db");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("ACCOUNTS_DB_URL", POSTGRES::getJdbcUrl);
        registry.add("ACCOUNTS_DB_USERNAME", POSTGRES::getUsername);
        registry.add("ACCOUNTS_DB_PASSWORD", POSTGRES::getPassword);
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("KEYCLOAK_ISSUER_URI", () -> "http://localhost:8090/realms/swiftdrop");
        registry.add("KEYCLOAK_JWKS_URI", () -> "http://localhost:8090/realms/swiftdrop/protocol/openid-connect/certs");
        registry.add("JWT_AUDIENCE", () -> "swiftdrop-api");
    }

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StaffProfileRepository staffProfileRepository;

    @Autowired
    private StaffProfileService staffProfileService;

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("Flyway V3 migration succeeds and creates staff_profiles table")
    void flywayMigration_succeeds() {
        var info = flyway.info().current();
        assertThat(info).isNotNull();
        assertThat(Integer.parseInt(info.getVersion().getVersion())).isGreaterThanOrEqualTo(3);

        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_name = 'staff_profiles'",
                Integer.class
        );
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("Unique constraint on keycloak_sub prevents duplicate staff rows")
    void duplicateSubject_rejectedByDatabase() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        String subject = "test-sub-unique-" + UUID.randomUUID();
        Instant now = Instant.now();

        jdbcTemplate.update("""
                INSERT INTO staff_profiles (id, keycloak_sub, staff_id, staff_role, department, assigned_zone, status, created_at, updated_at)
                VALUES (?, ?, ?, 'COURIER', 'FIELD_LOGISTICS', 'ZONE-A', 'ACTIVE', ?, ?)
                """, id1, subject, "STF-U1-" + id1, Timestamp.from(now), Timestamp.from(now));

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO staff_profiles (id, keycloak_sub, staff_id, staff_role, department, assigned_zone, status, created_at, updated_at)
                VALUES (?, ?, ?, 'COURIER', 'FIELD_LOGISTICS', 'ZONE-B', 'ACTIVE', ?, ?)
                """, id2, subject, "STF-U2-" + id2, Timestamp.from(now), Timestamp.from(now)))
                .hasMessageContaining("uq_staff_profiles_keycloak_sub");
    }

    @Test
    @DisplayName("Unique constraint on staff_id prevents duplicate employee identifiers")
    void duplicateStaffId_rejectedByDatabase() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        String staffId = "STF-DUP-" + UUID.randomUUID();
        Instant now = Instant.now();

        jdbcTemplate.update("""
                INSERT INTO staff_profiles (id, keycloak_sub, staff_id, staff_role, department, assigned_zone, status, created_at, updated_at)
                VALUES (?, ?, ?, 'COURIER', 'FIELD_LOGISTICS', 'ZONE-A', 'ACTIVE', ?, ?)
                """, id1, "sub-1-" + id1, staffId, Timestamp.from(now), Timestamp.from(now));

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO staff_profiles (id, keycloak_sub, staff_id, staff_role, department, assigned_zone, status, created_at, updated_at)
                VALUES (?, ?, ?, 'COURIER', 'FIELD_LOGISTICS', 'ZONE-B', 'ACTIVE', ?, ?)
                """, id2, "sub-2-" + id2, staffId, Timestamp.from(now), Timestamp.from(now)))
                .hasMessageContaining("uq_staff_profiles_staff_id");
    }

    @Test
    @DisplayName("Staff identity immutability trigger rejects UPDATE to keycloak_sub")
    void immutabilityTrigger_rejectsSubjectChange() {
        UUID id = UUID.randomUUID();
        String originalSub = "orig-sub-" + id;
        Instant now = Instant.now();

        jdbcTemplate.update("""
                INSERT INTO staff_profiles (id, keycloak_sub, staff_id, staff_role, department, assigned_zone, status, created_at, updated_at)
                VALUES (?, ?, ?, 'DISPATCHER', 'DISPATCH_OPERATIONS', NULL, 'ACTIVE', ?, ?)
                """, id, originalSub, "STF-IMM-" + id, Timestamp.from(now), Timestamp.from(now));

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE staff_profiles SET keycloak_sub = 'tampered-sub' WHERE id = ?", id))
                .hasMessageContaining("Staff identity binding is immutable");
    }

    @Test
    @DisplayName("Operational update to status or assigned_zone succeeds")
    void operationalUpdate_succeeds() {
        UUID id = UUID.randomUUID();
        String sub = "op-sub-" + id;
        Instant now = Instant.now();

        jdbcTemplate.update("""
                INSERT INTO staff_profiles (id, keycloak_sub, staff_id, staff_role, department, assigned_zone, status, created_at, updated_at)
                VALUES (?, ?, ?, 'COURIER', 'FIELD_LOGISTICS', 'ZONE-OLD', 'ACTIVE', ?, ?)
                """, id, sub, "STF-OP-" + id, Timestamp.from(now), Timestamp.from(now));

        int updated = jdbcTemplate.update(
                "UPDATE staff_profiles SET assigned_zone = 'ZONE-NEW', status = 'SUSPENDED' WHERE id = ?", id);
        assertThat(updated).isEqualTo(1);

        String newZone = jdbcTemplate.queryForObject(
                "SELECT assigned_zone FROM staff_profiles WHERE id = ?", String.class, id);
        String newStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM staff_profiles WHERE id = ?", String.class, id);

        assertThat(newZone).isEqualTo("ZONE-NEW");
        assertThat(newStatus).isEqualTo("SUSPENDED");
    }
}
