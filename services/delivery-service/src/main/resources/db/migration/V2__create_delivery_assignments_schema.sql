-- ==============================================================================
-- SwiftDrop Delivery Operations: Courier Assignments Schema (Issue #24 / LATER-08)
-- Database: delivery_db
-- Owner: delivery_app
--
-- Single-active-courier assignment model per DEC-ASG-01..07.
-- Enforces at most one active assignment per parcel via partial unique index.
-- Retains immutable historical records for previous assignments.
-- ==============================================================================

CREATE TABLE delivery_assignments (
    assignment_id UUID PRIMARY KEY,
    parcel_id UUID NOT NULL REFERENCES parcels(parcel_id) ON DELETE RESTRICT,
    courier_sub VARCHAR(64) NOT NULL,
    assigned_by VARCHAR(64) NOT NULL,
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    ended_at TIMESTAMPTZ,
    active BOOLEAN NOT NULL DEFAULT true
);

-- Partial unique index ensuring at most one active courier assignment per parcel
CREATE UNIQUE INDEX uq_delivery_assignments_active_parcel 
ON delivery_assignments(parcel_id) 
WHERE active = true;

CREATE INDEX idx_delivery_assignments_courier ON delivery_assignments(courier_sub, active);
CREATE INDEX idx_delivery_assignments_parcel ON delivery_assignments(parcel_id);
