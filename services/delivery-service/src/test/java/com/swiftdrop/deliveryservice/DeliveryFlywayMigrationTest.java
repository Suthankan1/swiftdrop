package com.swiftdrop.deliveryservice;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class DeliveryFlywayMigrationTest {

    @Test
    @DisplayName("Verify V1 Flyway migration script contains all required tables, constraints, and zero cross-service references")
    void verifyV1FlywayMigrationScript() throws Exception {
        ClassPathResource resource = new ClassPathResource("db/migration/V1__create_delivery_schema.sql");
        assertThat(resource.exists()).isTrue();

        String sql;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            sql = reader.lines().collect(Collectors.joining("\n"));
        }

        // Table 1: parcels
        assertThat(sql).contains("CREATE TABLE parcels");
        assertThat(sql).contains("parcel_id UUID PRIMARY KEY");
        assertThat(sql).contains("tracking_number VARCHAR(32) NOT NULL UNIQUE");
        assertThat(sql).contains("sender_sub VARCHAR(64) NOT NULL");
        assertThat(sql).contains("chk_parcel_status CHECK (");
        assertThat(sql).contains("'CREATED', 'PICKED_UP', 'IN_TRANSIT', 'OUT_FOR_DELIVERY', 'DELIVERED', 'FAILED'");
        assertThat(sql).contains("version BIGINT NOT NULL DEFAULT 1");
        assertThat(sql).contains("idx_parcels_sender_sub");
        assertThat(sql).contains("idx_parcels_status");

        // Table 2: status_history
        assertThat(sql).contains("CREATE TABLE status_history");
        assertThat(sql).contains("history_id UUID PRIMARY KEY");
        assertThat(sql).contains("REFERENCES parcels(parcel_id)");
        assertThat(sql).contains("resulting_version BIGINT NOT NULL");
        assertThat(sql).contains("chk_status_history_new_status CHECK (");
        assertThat(sql).contains("chk_status_history_prev_status CHECK (");
        assertThat(sql).contains("idx_status_history_parcel_version");
        assertThat(sql).contains("idx_status_history_occurred_at");

        // Table 3: idempotency_records
        assertThat(sql).contains("CREATE TABLE idempotency_records");
        assertThat(sql).contains("record_id UUID PRIMARY KEY");
        assertThat(sql).contains("uq_idempotency_principal_key UNIQUE (principal_or_tenant, idempotency_key)");
        assertThat(sql).contains("chk_idempotency_status CHECK (");
        assertThat(sql).contains("idx_idempotency_expires_at");

        // Strict cross-service boundary verification
        assertThat(sql).doesNotContain("accounts_db");
        assertThat(sql).doesNotContain("communications_db");
        assertThat(sql).doesNotContain("pricing_db");
        assertThat(sql).doesNotContain("REFERENCES merchants");
        assertThat(sql).doesNotContain("REFERENCES account_profiles");
        assertThat(sql).doesNotContain("REFERENCES staff_profiles");
        assertThat(sql).doesNotContain("REFERENCES quotes");
    }

    @Test
    @DisplayName("Verify V6 Flyway migration script contains merchant booking columns, indexes, and zero cross-service references")
    void verifyV6FlywayMigrationScript() throws Exception {
        ClassPathResource resource = new ClassPathResource("db/migration/V6__add_booking_fields_and_quote_uniqueness.sql");
        assertThat(resource.exists()).isTrue();

        String sql;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            sql = reader.lines().collect(Collectors.joining("\n"));
        }

        assertThat(sql).contains("ALTER TABLE parcels");
        assertThat(sql).contains("ADD COLUMN IF NOT EXISTS merchant_reference");
        assertThat(sql).contains("ADD COLUMN IF NOT EXISTS pickup_address");
        assertThat(sql).contains("ADD COLUMN IF NOT EXISTS dropoff_address");
        assertThat(sql).contains("ADD COLUMN IF NOT EXISTS recipient_name");
        assertThat(sql).contains("ADD COLUMN IF NOT EXISTS recipient_phone");
        assertThat(sql).contains("ADD COLUMN IF NOT EXISTS delivery_notes");
        assertThat(sql).contains("ADD COLUMN IF NOT EXISTS quote_snapshot");
        assertThat(sql).contains("CREATE UNIQUE INDEX IF NOT EXISTS uq_parcels_quote_id ON parcels (quote_id) WHERE quote_id IS NOT NULL");
        assertThat(sql).contains("CREATE INDEX IF NOT EXISTS idx_parcels_merchant_reference ON parcels (merchant_id, merchant_reference)");

        // Strict cross-service boundary verification
        assertThat(sql).doesNotContain("accounts_db");
        assertThat(sql).doesNotContain("communications_db");
        assertThat(sql).doesNotContain("pricing_db");
        assertThat(sql).doesNotContain("REFERENCES merchants");
        assertThat(sql).doesNotContain("REFERENCES account_profiles");
        assertThat(sql).doesNotContain("REFERENCES quotes");
    }

    @Test
    @DisplayName("Verify V7 Flyway migration script contains assignment audit table, indexes, backfill, and zero cross-service references")
    void verifyV7FlywayMigrationScript() throws Exception {
        ClassPathResource resource = new ClassPathResource("db/migration/V7__create_assignment_audit_schema.sql");
        assertThat(resource.exists()).isTrue();

        String sql;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            sql = reader.lines().collect(Collectors.joining("\n"));
        }

        assertThat(sql).contains("CREATE TABLE delivery_assignment_audits");
        assertThat(sql).contains("audit_id UUID PRIMARY KEY");
        assertThat(sql).contains("parcel_id UUID NOT NULL REFERENCES parcels(parcel_id)");
        assertThat(sql).contains("assignment_id UUID NOT NULL REFERENCES delivery_assignments(assignment_id)");
        assertThat(sql).contains("courier_sub VARCHAR(64) NOT NULL");
        assertThat(sql).contains("action VARCHAR(32) NOT NULL");
        assertThat(sql).contains("actor_sub VARCHAR(64) NOT NULL");
        assertThat(sql).contains("actor_role VARCHAR(32) NOT NULL");
        assertThat(sql).contains("parcel_version BIGINT NOT NULL");
        assertThat(sql).contains("occurred_at TIMESTAMPTZ NOT NULL DEFAULT NOW()");
        assertThat(sql).contains("CREATE INDEX idx_assignment_audits_parcel ON delivery_assignment_audits");
        assertThat(sql).contains("CREATE INDEX idx_assignment_audits_assignment ON delivery_assignment_audits");
        assertThat(sql).contains("INSERT INTO delivery_assignment_audits");

        // Strict cross-service boundary verification
        assertThat(sql).doesNotContain("accounts_db");
        assertThat(sql).doesNotContain("communications_db");
        assertThat(sql).doesNotContain("pricing_db");
        assertThat(sql).doesNotContain("REFERENCES merchants");
        assertThat(sql).doesNotContain("REFERENCES account_profiles");
        assertThat(sql).doesNotContain("REFERENCES staff_profiles");
        assertThat(sql).doesNotContain("REFERENCES quotes");
    }
}
