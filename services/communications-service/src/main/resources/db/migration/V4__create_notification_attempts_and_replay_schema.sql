-- SwiftDrop Communications Service - Issue #117 Schema Migration
-- Milestone: November 10 Baseline (Architecture v1.3)
-- Schema Ownership: communications_db
-- Bounded Notification Retry, Poison-Event Quarantine and Inspected Replay

-- 1. Extend notifications table with worker claim and recovery metadata
ALTER TABLE notifications
    ADD COLUMN claim_token VARCHAR(64),
    ADD COLUMN claimed_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN lease_expires_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN last_failure_category VARCHAR(50);

CREATE INDEX idx_notifications_claim ON notifications (status, next_attempt_at, lease_expires_at);

-- 2. Durable Notification Attempt History
CREATE TABLE notification_attempts (
    attempt_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    notification_id UUID NOT NULL REFERENCES notifications(notification_id) ON DELETE CASCADE,
    attempt_number INT NOT NULL,
    channel VARCHAR(50) NOT NULL,
    outcome VARCHAR(30) NOT NULL,
    failure_category VARCHAR(50),
    safe_error_code VARCHAR(100),
    duration_ms INT,
    attempted_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT chk_notification_attempts_num CHECK (attempt_number >= 1),
    CONSTRAINT chk_notification_attempts_outcome CHECK (outcome IN ('SUCCESS', 'TRANSIENT_FAILURE', 'PERMANENT_FAILURE', 'SECURITY_FAILURE', 'TIMEOUT'))
);

CREATE INDEX idx_notification_attempts_notification_id ON notification_attempts (notification_id);
CREATE INDEX idx_notification_attempts_attempted_at ON notification_attempts (attempted_at);

-- 3. Notification Replay Audit Records
CREATE TABLE notification_replays (
    replay_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    notification_id UUID NOT NULL REFERENCES notifications(notification_id) ON DELETE RESTRICT,
    original_event_id UUID NOT NULL,
    actor_sub VARCHAR(255) NOT NULL,
    actor_role VARCHAR(100) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    previous_status VARCHAR(30) NOT NULL,
    decision VARCHAR(30) NOT NULL,
    replayed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_notification_replays_actor_sub CHECK (LENGTH(TRIM(actor_sub)) > 0),
    CONSTRAINT chk_notification_replays_reason CHECK (LENGTH(TRIM(reason)) > 0),
    CONSTRAINT chk_notification_replays_prev_status CHECK (previous_status IN ('FAILED', 'EXHAUSTED')),
    CONSTRAINT chk_notification_replays_decision CHECK (decision IN ('ACCEPTED', 'REJECTED'))
);

CREATE INDEX idx_notification_replays_notification_id ON notification_replays (notification_id);
CREATE INDEX idx_notification_replays_original_event_id ON notification_replays (original_event_id);

-- 4. Poison-Event Quarantine
CREATE TABLE poison_event_quarantine (
    quarantine_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    consumer_group VARCHAR(100) NOT NULL,
    source_topic VARCHAR(100) NOT NULL,
    source_partition INT NOT NULL,
    source_offset BIGINT NOT NULL,
    event_id_if_valid UUID,
    event_type_if_valid VARCHAR(100),
    schema_version_if_valid VARCHAR(50),
    failure_category VARCHAR(50) NOT NULL,
    safe_error_summary VARCHAR(500) NOT NULL,
    payload_digest VARCHAR(64) NOT NULL,
    correlation_reference_if_safe VARCHAR(64),
    status VARCHAR(30) NOT NULL DEFAULT 'QUARANTINED',
    quarantined_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    replayed_at TIMESTAMP WITH TIME ZONE,
    replayed_by_actor_sub VARCHAR(255),
    replay_reason VARCHAR(500),
    CONSTRAINT uq_poison_event UNIQUE (consumer_group, source_topic, source_partition, source_offset),
    CONSTRAINT chk_poison_event_status CHECK (status IN ('QUARANTINED', 'REPLAYED', 'DISCARDED'))
);

CREATE INDEX idx_poison_quarantine_event_id ON poison_event_quarantine (event_id_if_valid);
CREATE INDEX idx_poison_quarantine_status ON poison_event_quarantine (status);
CREATE INDEX idx_poison_quarantine_quarantined_at ON poison_event_quarantine (quarantined_at);
