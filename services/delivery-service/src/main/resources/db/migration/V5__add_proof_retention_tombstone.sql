-- ==============================================================================
-- SwiftDrop Delivery Operations: Proof Retention & Privacy Tombstone (Issue #124 / N10-QA-05)
-- Database: delivery_db
-- Owner: delivery_app
--
-- Additive migration for privacy-safe proof retention lifecycle and tombstones.
-- Zero public storage URLs; preserves parcel master and status audit history.
-- ==============================================================================

ALTER TABLE proof_of_deliveries
    ADD COLUMN retention_status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN purged_at TIMESTAMPTZ;

ALTER TABLE proof_of_deliveries
    ADD CONSTRAINT chk_proof_retention_status CHECK (
        retention_status IN ('ACTIVE', 'PURGED')
    );

-- Allow object_reference to be safely cleared upon privacy-safe retention purge
ALTER TABLE proof_of_deliveries
    ALTER COLUMN object_reference DROP NOT NULL;

CREATE INDEX idx_proof_retention_status ON proof_of_deliveries(retention_status);
CREATE INDEX idx_proof_retention_candidates ON proof_of_deliveries(retention_status, created_at)
    WHERE retention_status = 'ACTIVE';
