-- ==============================================================================
-- SwiftDrop Pricing & Zones Schema Migration
-- Bounded Context: Pricing & Zones (pricing_db owned by pricing_app)
-- Specification: Issue #108 (N10-B03) - Immutable Expiring Quote Snapshots
-- ==============================================================================

CREATE TABLE pricing_quotes (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    rate_version_id UUID NOT NULL REFERENCES pricing_rate_versions (id) ON DELETE RESTRICT,
    rate_version_code VARCHAR(64) NOT NULL REFERENCES pricing_rate_versions (version_code) ON DELETE RESTRICT,
    rate_rule_id UUID REFERENCES pricing_rate_rules (id) ON DELETE RESTRICT,
    pickup_zone_code VARCHAR(32) NOT NULL REFERENCES pricing_zones (zone_code) ON DELETE RESTRICT,
    dropoff_zone_code VARCHAR(32) NOT NULL REFERENCES pricing_zones (zone_code) ON DELETE RESTRICT,
    length_cm INT NOT NULL CHECK (length_cm >= 1 AND length_cm <= 150),
    width_cm INT NOT NULL CHECK (width_cm >= 1 AND width_cm <= 150),
    height_cm INT NOT NULL CHECK (height_cm >= 1 AND height_cm <= 150),
    physical_weight_kg NUMERIC(6, 2) NOT NULL CHECK (physical_weight_kg >= 0.01 AND physical_weight_kg <= 30.00),
    volumetric_weight_kg NUMERIC(6, 2) NOT NULL CHECK (volumetric_weight_kg >= 0.00),
    chargeable_weight_kg NUMERIC(6, 2) NOT NULL CHECK (chargeable_weight_kg >= 0.01 AND chargeable_weight_kg <= 30.00),
    size_category VARCHAR(32) NOT NULL CHECK (size_category IN ('SMALL', 'MEDIUM', 'LARGE', 'OVERSIZED')),
    base_fee_minor_units BIGINT NOT NULL CHECK (base_fee_minor_units >= 0),
    weight_surcharge_minor_units BIGINT NOT NULL CHECK (weight_surcharge_minor_units >= 0),
    total_amount_minor_units BIGINT NOT NULL CHECK (total_amount_minor_units >= 0),
    currency VARCHAR(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    subject_sub VARCHAR(255),
    tenant_id VARCHAR(255),
    is_anonymous BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT chk_quote_expiry_interval CHECK (expires_at > created_at),
    CONSTRAINT chk_quote_ownership_consistency CHECK (
        (is_anonymous = true AND subject_sub IS NULL) OR
        (is_anonymous = false AND subject_sub IS NOT NULL)
    )
);

CREATE INDEX idx_pricing_quotes_expires_at ON pricing_quotes (expires_at);
CREATE INDEX idx_pricing_quotes_subject_sub ON pricing_quotes (subject_sub);
CREATE INDEX idx_pricing_quotes_tenant_id ON pricing_quotes (tenant_id);
