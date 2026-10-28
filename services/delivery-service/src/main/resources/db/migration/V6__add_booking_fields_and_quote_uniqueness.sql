-- ==============================================================================
-- SwiftDrop Delivery Operations: Booking Fields, Merchant Reference & Quote Uniqueness
-- Database: delivery_db
-- Owner: delivery_app
-- Linked Issue: #129 (N10-B12)
--
-- Aligns canonical parcels schema with merchant and customer order booking intake.
-- Enforces global single-use quote uniqueness across all booking channels.
-- ==============================================================================

ALTER TABLE parcels
    ADD COLUMN IF NOT EXISTS merchant_reference VARCHAR(255),
    ADD COLUMN IF NOT EXISTS pickup_address VARCHAR(2000),
    ADD COLUMN IF NOT EXISTS dropoff_address VARCHAR(2000),
    ADD COLUMN IF NOT EXISTS recipient_name VARCHAR(255),
    ADD COLUMN IF NOT EXISTS recipient_phone VARCHAR(50),
    ADD COLUMN IF NOT EXISTS delivery_notes VARCHAR(2000),
    ADD COLUMN IF NOT EXISTS quote_snapshot JSONB;

-- Enforce global single-consumption invariant for quotes across Customer and Merchant channels
CREATE UNIQUE INDEX IF NOT EXISTS uq_parcels_quote_id ON parcels (quote_id) WHERE quote_id IS NOT NULL;

-- Index merchant reference per tenant for authorized lookup and reconciliation
CREATE INDEX IF NOT EXISTS idx_parcels_merchant_reference ON parcels (merchant_id, merchant_reference)
    WHERE merchant_id IS NOT NULL AND merchant_reference IS NOT NULL;
