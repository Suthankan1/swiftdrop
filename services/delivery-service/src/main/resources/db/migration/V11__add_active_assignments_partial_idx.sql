-- ==============================================================================
-- SwiftDrop Delivery Operations: Optimize Active Courier Queries (Audit PERF-01)
-- Database: delivery_db
-- Owner: delivery_app
--
-- Partial B-Tree index covering active courier assignments ordered by assignment time.
-- Enables direct index-only / partial scans for active courier workload and active
-- assignment retrieval without reading inactive historical assignment records.
-- ==============================================================================

CREATE INDEX IF NOT EXISTS idx_delivery_assignments_active_courier
ON delivery_assignments (courier_sub, assigned_at DESC)
WHERE active = true;
