-- ==============================================================================
-- SwiftDrop Delivery Operations: Courier Assignment Audit Schema (Issue #125 / N10-B23)
-- Database: delivery_db
-- Owner: delivery_app
--
-- Append-only audit log for assignment lifecycle actions per DEC-ASG-07.
-- Preserves immutable records of initial assignment, reassignment, and closure.
-- Decoupled from parcel status_history.
-- ==============================================================================

CREATE TABLE delivery_assignment_audits (
    audit_id UUID PRIMARY KEY,
    parcel_id UUID NOT NULL REFERENCES parcels(parcel_id) ON DELETE RESTRICT,
    assignment_id UUID NOT NULL REFERENCES delivery_assignments(assignment_id) ON DELETE RESTRICT,
    courier_sub VARCHAR(64) NOT NULL,
    action VARCHAR(32) NOT NULL,
    actor_sub VARCHAR(64) NOT NULL,
    actor_role VARCHAR(32) NOT NULL,
    previous_courier_sub VARCHAR(64),
    previous_assignment_id UUID REFERENCES delivery_assignments(assignment_id) ON DELETE RESTRICT,
    reason VARCHAR(255),
    parcel_version BIGINT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_assignment_audits_parcel ON delivery_assignment_audits(parcel_id, occurred_at DESC);
CREATE INDEX idx_assignment_audits_assignment ON delivery_assignment_audits(assignment_id);
CREATE INDEX idx_assignment_audits_courier ON delivery_assignment_audits(courier_sub);

-- Backfill audit records for existing initial assignments if any
INSERT INTO delivery_assignment_audits (
    audit_id,
    parcel_id,
    assignment_id,
    courier_sub,
    action,
    actor_sub,
    actor_role,
    reason,
    parcel_version,
    occurred_at
)
SELECT
    assignment_id,
    parcel_id,
    assignment_id,
    courier_sub,
    'ASSIGNED',
    assigned_by,
    'DISPATCHER',
    'Initial assignment',
    1,
    assigned_at
FROM delivery_assignments
ON CONFLICT (audit_id) DO NOTHING;

