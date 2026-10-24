package com.swiftdrop.communicationsservice;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class CommunicationsFlywayMigrationTest {

    @Test
    @DisplayName("Verify V1 Flyway migration script contains all required tables, constraints, and zero cross-service references")
    void verifyV1FlywayMigrationScript() throws Exception {
        ClassPathResource resource = new ClassPathResource("db/migration/V1__create_communications_schema.sql");
        assertThat(resource.exists()).isTrue();

        String sql;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            sql = reader.lines().collect(Collectors.joining("\n"));
        }

        // Table 1: consumed_events
        assertThat(sql).contains("CREATE TABLE consumed_events");
        assertThat(sql).contains("CONSTRAINT pk_consumed_events PRIMARY KEY (consumer_name, event_id)");
        assertThat(sql).contains("chk_consumed_events_consumer_name");
        assertThat(sql).contains("chk_consumed_events_event_type");

        // Table 2: notifications
        assertThat(sql).contains("CREATE TABLE notifications");
        assertThat(sql).contains("notification_id UUID PRIMARY KEY");
        assertThat(sql).contains("CONSTRAINT uq_notifications_event_recipient_channel UNIQUE (event_id, recipient_sub, channel)");
        assertThat(sql).contains("CONSTRAINT chk_notifications_channel CHECK (channel IN ('EMAIL', 'SMS_MOCK'))");
        assertThat(sql).contains("CONSTRAINT chk_notifications_status CHECK (status IN ('PENDING', 'PROCESSING', 'SENT', 'FAILED', 'EXHAUSTED'))");
        assertThat(sql).contains("idx_notifications_pending");

        // Table 3: webhook_subscriptions
        assertThat(sql).contains("CREATE TABLE webhook_subscriptions");
        assertThat(sql).contains("subscription_id UUID PRIMARY KEY");
        assertThat(sql).contains("CONSTRAINT chk_webhook_subscriptions_https CHECK (https_url ~* '^https://')");
        assertThat(sql).contains("CONSTRAINT chk_webhook_subscriptions_secret_ref");

        // Table 4: webhook_deliveries
        assertThat(sql).contains("CREATE TABLE webhook_deliveries");
        assertThat(sql).contains("delivery_id UUID PRIMARY KEY");
        assertThat(sql).contains("REFERENCES webhook_subscriptions(subscription_id)");
        assertThat(sql).contains("CONSTRAINT uq_webhook_deliveries_subscription_event UNIQUE (subscription_id, event_id)");
        assertThat(sql).contains("CONSTRAINT chk_webhook_deliveries_status CHECK (status IN ('PENDING', 'PROCESSING', 'SUCCESS', 'FAILED', 'EXHAUSTED'))");
        assertThat(sql).contains("idx_webhook_deliveries_pending");

        // Table 5: webhook_attempts
        assertThat(sql).contains("CREATE TABLE webhook_attempts");
        assertThat(sql).contains("attempt_id UUID PRIMARY KEY");
        assertThat(sql).contains("REFERENCES webhook_deliveries(delivery_id)");

        // Strict cross-service boundary verification
        assertThat(sql).doesNotContain("accounts_db");
        assertThat(sql).doesNotContain("delivery_db");
        assertThat(sql).doesNotContain("pricing_db");
        assertThat(sql).doesNotContain("REFERENCES merchants");
        assertThat(sql).doesNotContain("REFERENCES parcels");
        assertThat(sql).doesNotContain("REFERENCES account_profiles");
        assertThat(sql).doesNotContain("REFERENCES staff_profiles");
    }
}
