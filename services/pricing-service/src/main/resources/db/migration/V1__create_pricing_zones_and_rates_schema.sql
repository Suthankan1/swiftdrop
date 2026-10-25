-- ==============================================================================
-- SwiftDrop Pricing & Zones Schema Migration
-- Bounded Context: Pricing & Zones (pricing_db owned by pricing_app)
-- Specification: DEC-PRC-01 to DEC-PRC-08 & Architecture Baseline v1.3
-- ==============================================================================

-- 1. Operational Pricing Zones
CREATE TABLE pricing_zones (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    zone_code VARCHAR(32) NOT NULL UNIQUE,
    name VARCHAR(128) NOT NULL,
    description VARCHAR(512) NOT NULL,
    standard_transit_hours INT NOT NULL CHECK (standard_transit_hours > 0),
    active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_pricing_zones_active ON pricing_zones (active);

-- 2. Versioned Rate Cards
CREATE TABLE pricing_rate_versions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version_code VARCHAR(64) NOT NULL UNIQUE,
    currency VARCHAR(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    status VARCHAR(32) NOT NULL CHECK (status IN ('DRAFT', 'ACTIVE', 'SUPERSEDED', 'ARCHIVED')),
    effective_from TIMESTAMP WITH TIME ZONE NOT NULL,
    effective_to TIMESTAMP WITH TIME ZONE,
    description VARCHAR(512),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    activated_at TIMESTAMP WITH TIME ZONE,
    archived_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT chk_effective_interval CHECK (effective_to IS NULL OR effective_to > effective_from)
);

-- Invariant: At most one active rate card per currency
CREATE UNIQUE INDEX idx_unique_active_rate_version ON pricing_rate_versions (currency) WHERE status = 'ACTIVE';

-- 3. Versioned Rate Rules & Matrix
CREATE TABLE pricing_rate_rules (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    rate_version_id UUID NOT NULL REFERENCES pricing_rate_versions (id) ON DELETE RESTRICT,
    pickup_zone_code VARCHAR(32) NOT NULL REFERENCES pricing_zones (zone_code) ON DELETE RESTRICT,
    dropoff_zone_code VARCHAR(32) NOT NULL REFERENCES pricing_zones (zone_code) ON DELETE RESTRICT,
    size_category VARCHAR(32) NOT NULL CHECK (size_category IN ('SMALL', 'MEDIUM', 'LARGE', 'OVERSIZED')),
    min_weight_kg NUMERIC(6, 2) NOT NULL CHECK (min_weight_kg >= 0.01),
    max_weight_kg NUMERIC(6, 2) NOT NULL CHECK (max_weight_kg <= 30.00 AND max_weight_kg >= min_weight_kg),
    base_fee_minor_units BIGINT NOT NULL CHECK (base_fee_minor_units >= 0),
    per_kg_surcharge_minor_units BIGINT NOT NULL DEFAULT 0 CHECK (per_kg_surcharge_minor_units >= 0),
    weight_threshold_kg NUMERIC(6, 2) NOT NULL DEFAULT 0.00 CHECK (weight_threshold_kg >= 0.00),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_rate_rule_band UNIQUE (
        rate_version_id,
        pickup_zone_code,
        dropoff_zone_code,
        size_category,
        min_weight_kg,
        max_weight_kg
    )
);

CREATE INDEX idx_rate_rules_lookup ON pricing_rate_rules (
    rate_version_id,
    pickup_zone_code,
    dropoff_zone_code,
    size_category
);
