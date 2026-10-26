-- ==============================================================================
-- SwiftDrop Delivery Operations: Proof of Delivery Schema (Issue #95 / N10-B13)
-- Database: delivery_db
-- Owner: delivery_app
--
-- Persists proof-of-delivery metadata, private object references, and durable
-- cleanup tasks for failed store compensation.
-- Zero public URLs; binary object data is stored strictly in private object store.
-- ==============================================================================

CREATE TABLE proof_of_deliveries (
    proof_id UUID PRIMARY KEY,
    parcel_id UUID NOT NULL REFERENCES parcels(parcel_id) ON DELETE RESTRICT,
    object_reference VARCHAR(128) NOT NULL,
    evidence_type VARCHAR(32) NOT NULL DEFAULT 'PHOTO',
    content_type VARCHAR(64) NOT NULL DEFAULT 'image/jpeg',
    content_length BIGINT NOT NULL,
    recipient_name VARCHAR(128),
    delivery_note VARCHAR(512),
    uploaded_by_sub VARCHAR(64) NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_proof_parcel_id UNIQUE (parcel_id),
    CONSTRAINT chk_proof_evidence_type CHECK (
        evidence_type IN ('PHOTO', 'SIGNATURE', 'PHOTO_OR_SIGNATURE')
    )
);

CREATE INDEX idx_proof_of_deliveries_parcel ON proof_of_deliveries(parcel_id);
CREATE INDEX idx_proof_of_deliveries_uploader ON proof_of_deliveries(uploaded_by_sub);

CREATE TABLE proof_cleanup_tasks (
    task_id UUID PRIMARY KEY,
    object_reference VARCHAR(128) NOT NULL,
    reason VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    retry_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_attempt_at TIMESTAMPTZ,
    CONSTRAINT chk_cleanup_status CHECK (
        status IN ('PENDING', 'COMPLETED', 'FAILED')
    )
);

CREATE INDEX idx_proof_cleanup_tasks_status ON proof_cleanup_tasks(status);
