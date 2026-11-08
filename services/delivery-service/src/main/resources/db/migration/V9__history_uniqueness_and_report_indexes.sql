-- One history row per parcel version; replaces the non-unique index.
CREATE UNIQUE INDEX IF NOT EXISTS uq_status_history_parcel_version ON status_history(parcel_id, resulting_version);
DROP INDEX IF EXISTS idx_status_history_parcel_version;

-- Date-range reports and newest-first lists.
CREATE INDEX IF NOT EXISTS idx_parcels_created_at ON parcels(created_at);

-- Expired idempotency records are purged on a schedule.
CREATE INDEX IF NOT EXISTS idx_idempotency_expires_at ON idempotency_records(expires_at);
