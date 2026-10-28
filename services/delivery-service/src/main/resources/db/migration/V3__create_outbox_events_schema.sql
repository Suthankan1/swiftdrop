-- ==============================================================================
-- SwiftDrop Delivery Operations: Transactional Outbox Schema (Issue #31 / LATER-13)
-- Database: delivery_db
-- Owner: delivery_app
--
-- Implements DEC-EVT-01..04 for transactional outbox pattern.
-- Guarantees atomic commit of parcel state, status history, and outbox event
-- within a single local PostgreSQL database transaction.
-- Bounded relay polls outbox_events and publishes to Kafka with at-least-once guarantee.
-- ==============================================================================

CREATE TABLE outbox_events (
    event_id UUID PRIMARY KEY,
    aggregate_type VARCHAR(32) NOT NULL DEFAULT 'PARCEL',
    aggregate_id UUID NOT NULL REFERENCES parcels(parcel_id) ON DELETE RESTRICT,
    aggregate_version BIGINT NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    schema_version VARCHAR(16) NOT NULL DEFAULT '1.0.0',
    payload JSONB NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    attempt_count INT NOT NULL DEFAULT 0,
    last_attempt_at TIMESTAMPTZ,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_error TEXT,
    acknowledged_at TIMESTAMPTZ,
    claimed_by VARCHAR(64),
    claim_expires_at TIMESTAMPTZ,
    topic VARCHAR(128) NOT NULL DEFAULT 'parcel.lifecycle.v1',
    partition_key VARCHAR(64) NOT NULL,
    CONSTRAINT chk_outbox_status CHECK (status IN ('PENDING', 'IN_FLIGHT', 'PUBLISHED', 'FAILED', 'DEAD_LETTER')),
    CONSTRAINT uq_outbox_aggregate_version UNIQUE (aggregate_id, aggregate_version)
);

-- Partial index for polling ready pending or retryable failed events
CREATE INDEX idx_outbox_pending_poll ON outbox_events (status, next_attempt_at, created_at) 
WHERE status IN ('PENDING', 'FAILED');

-- Index for ordering by aggregate version per parcel
CREATE INDEX idx_outbox_aggregate_version ON outbox_events (aggregate_id, aggregate_version);

-- Partial index for reclaiming expired in-flight claims
CREATE INDEX idx_outbox_claims ON outbox_events (status, claim_expires_at) 
WHERE status = 'IN_FLIGHT';
