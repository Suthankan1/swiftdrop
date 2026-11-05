-- ==============================================================================
-- SwiftDrop Delivery Operations: Optimize Merchant Parcel Listing (DB-001)
-- Database: delivery_db
-- Owner: delivery_app
--
-- Composite index to optimize querying and sorting parcels by merchant and created_at DESC.
-- ==============================================================================

CREATE INDEX IF NOT EXISTS idx_parcels_merchant_created ON parcels (merchant_id, created_at DESC) WHERE merchant_id IS NOT NULL;

