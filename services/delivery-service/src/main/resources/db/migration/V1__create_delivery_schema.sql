-- ==============================================================================
-- SwiftDrop Delivery Operations: Base Delivery Schema (Issue #126 / N10-B24)
-- Database: delivery_db
-- Owner: delivery_app
--
-- Establishes canonical parcel persistence, immutable chronological status history,
-- and idempotent mutation tracking.
-- Zero cross-service foreign keys: all external IDs (sender_sub, merchant_id,
-- quote_id, actor_sub, proof_id) are stored as opaque scalars.
-- ==============================================================================

-- 1. Canonical Parcels Aggregate Root Table
CREATE TABLE parcels (
    parcel_id UUID PRIMARY KEY,
    tracking_number VARCHAR(32) NOT NULL UNIQUE,
    sender_sub VARCHAR(64) NOT NULL,
    merchant_id UUID,
    quote_id UUID,
    rate_version VARCHAR(32),
    amount_minor_units BIGINT,
    currency VARCHAR(3) NOT NULL DEFAULT 'LKR',
    weight_kg NUMERIC(8, 3),
    status VARCHAR(20) NOT NULL DEFAULT 'CREATED',
    version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_parcel_status CHECK (
        status IN ('CREATED', 'PICKED_UP', 'IN_TRANSIT', 'OUT_FOR_DELIVERY', 'DELIVERED', 'FAILED')
    )
);

CREATE INDEX idx_parcels_sender_sub ON parcels(sender_sub);
CREATE INDEX idx_parcels_status ON parcels(status);
CREATE INDEX idx_parcels_merchant_id ON parcels(merchant_id) WHERE merchant_id IS NOT NULL;

-- 2. Append-Only Status Audit History Table
CREATE TABLE status_history (
    history_id UUID PRIMARY KEY,
    parcel_id UUID NOT NULL REFERENCES parcels(parcel_id) ON DELETE RESTRICT,
    previous_status VARCHAR(20),
    new_status VARCHAR(20) NOT NULL,
    reason VARCHAR(64),
    note TEXT,
    proof_id UUID,
    actor_sub VARCHAR(64) NOT NULL,
    resulting_version BIGINT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_status_history_new_status CHECK (
        new_status IN ('CREATED', 'PICKED_UP', 'IN_TRANSIT', 'OUT_FOR_DELIVERY', 'DELIVERED', 'FAILED')
    ),
    CONSTRAINT chk_status_history_prev_status CHECK (
        previous_status IS NULL OR previous_status IN ('CREATED', 'PICKED_UP', 'IN_TRANSIT', 'OUT_FOR_DELIVERY', 'DELIVERED', 'FAILED')
    )
);

CREATE INDEX idx_status_history_parcel_version ON status_history(parcel_id, resulting_version);
CREATE INDEX idx_status_history_occurred_at ON status_history(occurred_at);

-- 3. Idempotency Records Table for Mutation De-duplication
CREATE TABLE idempotency_records (
    record_id UUID PRIMARY KEY,
    principal_or_tenant VARCHAR(64) NOT NULL,
    idempotency_key VARCHAR(64) NOT NULL,
    request_digest VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'IN_PROGRESS',
    stored_result JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_idempotency_principal_key UNIQUE (principal_or_tenant, idempotency_key),
    CONSTRAINT chk_idempotency_status CHECK (
        status IN ('IN_PROGRESS', 'COMPLETED', 'FAILED')
    )
);

CREATE INDEX idx_idempotency_expires_at ON idempotency_records(expires_at);
