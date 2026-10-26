-- ==============================================================================
-- SwiftDrop Baseline Pricing Matrix Seed Data
-- Bounded Context: Pricing & Zones
-- Synthetic Test Tariff Data for RATE-2026-V1
-- ==============================================================================

-- 1. Seed Operational Pricing Zones (DEC-PRC-06)
INSERT INTO pricing_zones (zone_code, name, description, standard_transit_hours, active)
VALUES
    ('ZONE_1', 'Colombo Municipal District', 'Intra-city delivery covering Colombo postal codes 1 through 15.', 24, true),
    ('ZONE_2', 'Western Province Suburbs', 'Suburban and regional delivery across Greater Colombo and Western Province.', 48, true),
    ('ZONE_3', 'National / Island-wide', 'Island-wide delivery covering all districts outside Western Province.', 72, true)
ON CONFLICT (zone_code) DO NOTHING;

-- 2. Seed Baseline Active Rate Version (DEC-PRC-01, DEC-PRC-06)
INSERT INTO pricing_rate_versions (
    id,
    version_code,
    currency,
    status,
    effective_from,
    description,
    activated_at
)
VALUES (
    'a0000000-0000-0000-0000-000000000001',
    'RATE-2026-V1',
    'LKR',
    'ACTIVE',
    '2026-01-01 00:00:00+00',
    'Baseline 2026 Domestic Tariff Matrix (Synthetic Test Baseline)',
    '2026-01-01 00:00:00+00'
)
ON CONFLICT (version_code) DO NOTHING;

-- 3. Seed Matrix Rate Rules for RATE-2026-V1
-- Helper table for cross product of zone pairs
INSERT INTO pricing_rate_rules (
    rate_version_id,
    pickup_zone_code,
    dropoff_zone_code,
    size_category,
    min_weight_kg,
    max_weight_kg,
    base_fee_minor_units,
    per_kg_surcharge_minor_units,
    weight_threshold_kg
)
VALUES
    -- Intra-city: ZONE_1 -> ZONE_1
    ('a0000000-0000-0000-0000-000000000001', 'ZONE_1', 'ZONE_1', 'SMALL',     0.01,  1.00,  25000,     0, 1.00),
    ('a0000000-0000-0000-0000-000000000001', 'ZONE_1', 'ZONE_1', 'MEDIUM',    0.01,  5.00,  35000,  5000, 1.00),
    ('a0000000-0000-0000-0000-000000000001', 'ZONE_1', 'ZONE_1', 'LARGE',     0.01, 15.00,  60000,  7500, 5.00),
    ('a0000000-0000-0000-0000-000000000001', 'ZONE_1', 'ZONE_1', 'OVERSIZED', 0.01, 30.00, 120000, 10000, 15.00),

    -- Intra-suburban: ZONE_2 -> ZONE_2
    ('a0000000-0000-0000-0000-000000000001', 'ZONE_2', 'ZONE_2', 'SMALL',     0.01,  1.00,  30000,     0, 1.00),
    ('a0000000-0000-0000-0000-000000000001', 'ZONE_2', 'ZONE_2', 'MEDIUM',    0.01,  5.00,  45000,  5500, 1.00),
    ('a0000000-0000-0000-0000-000000000001', 'ZONE_2', 'ZONE_2', 'LARGE',     0.01, 15.00,  75000,  8000, 5.00),
    ('a0000000-0000-0000-0000-000000000001', 'ZONE_2', 'ZONE_2', 'OVERSIZED', 0.01, 30.00, 140000, 11000, 15.00),

    -- City <-> Suburban: ZONE_1 <-> ZONE_2
    ('a0000000-0000-0000-0000-000000000001', 'ZONE_1', 'ZONE_2', 'SMALL',     0.01,  1.00,  35000,     0, 1.00),
    ('a0000000-0000-0000-0000-000000000001', 'ZONE_1', 'ZONE_2', 'MEDIUM',    0.01,  5.00,  50000,  6000, 1.00),
    ('a0000000-0000-0000-0000-000000000001', 'ZONE_1', 'ZONE_2', 'LARGE',     0.01, 15.00,  85000,  8500, 5.00),
    ('a0000000-0000-0000-0000-000000000001', 'ZONE_1', 'ZONE_2', 'OVERSIZED', 0.01, 30.00, 150000, 12000, 15.00),

    ('a0000000-0000-0000-0000-000000000001', 'ZONE_2', 'ZONE_1', 'SMALL',     0.01,  1.00,  35000,     0, 1.00),
    ('a0000000-0000-0000-0000-000000000001', 'ZONE_2', 'ZONE_1', 'MEDIUM',    0.01,  5.00,  50000,  6000, 1.00),
    ('a0000000-0000-0000-0000-000000000001', 'ZONE_2', 'ZONE_1', 'LARGE',     0.01, 15.00,  85000,  8500, 5.00),
    ('a0000000-0000-0000-0000-000000000001', 'ZONE_2', 'ZONE_1', 'OVERSIZED', 0.01, 30.00, 150000, 12000, 15.00),

    -- National Cross-Zone: ZONE_1 <-> ZONE_3
    ('a0000000-0000-0000-0000-000000000001', 'ZONE_1', 'ZONE_3', 'SMALL',     0.01,  1.00,  50000,     0, 1.00),
    ('a0000000-0000-0000-0000-000000000001', 'ZONE_1', 'ZONE_3', 'MEDIUM',    0.01,  5.00,  75000,  8000, 1.00),
    ('a0000000-0000-0000-0000-000000000001', 'ZONE_1', 'ZONE_3', 'LARGE',     0.01, 15.00, 120000, 10000, 5.00),
    ('a0000000-0000-0000-0000-000000000001', 'ZONE_1', 'ZONE_3', 'OVERSIZED', 0.01, 30.00, 200000, 15000, 15.00),

    ('a0000000-0000-0000-0000-000000000001', 'ZONE_3', 'ZONE_1', 'SMALL',     0.01,  1.00,  50000,     0, 1.00),
    ('a0000000-0000-0000-0000-000000000001', 'ZONE_3', 'ZONE_1', 'MEDIUM',    0.01,  5.00,  75000,  8000, 1.00),
    ('a0000000-0000-0000-0000-000000000001', 'ZONE_3', 'ZONE_1', 'LARGE',     0.01, 15.00, 120000, 10000, 5.00),
    ('a0000000-0000-0000-0000-000000000001', 'ZONE_3', 'ZONE_1', 'OVERSIZED', 0.01, 30.00, 200000, 15000, 15.00),

    -- National Cross-Zone: ZONE_2 <-> ZONE_3
    ('a0000000-0000-0000-0000-000000000001', 'ZONE_2', 'ZONE_3', 'SMALL',     0.01,  1.00,  55000,     0, 1.00),
    ('a0000000-0000-0000-0000-000000000001', 'ZONE_2', 'ZONE_3', 'MEDIUM',    0.01,  5.00,  80000,  8500, 1.00),
    ('a0000000-0000-0000-0000-000000000001', 'ZONE_2', 'ZONE_3', 'LARGE',     0.01, 15.00, 130000, 11000, 5.00),
    ('a0000000-0000-0000-0000-000000000001', 'ZONE_2', 'ZONE_3', 'OVERSIZED', 0.01, 30.00, 210000, 16000, 15.00),

    ('a0000000-0000-0000-0000-000000000001', 'ZONE_3', 'ZONE_2', 'SMALL',     0.01,  1.00,  55000,     0, 1.00),
    ('a0000000-0000-0000-0000-000000000001', 'ZONE_3', 'ZONE_2', 'MEDIUM',    0.01,  5.00,  80000,  8500, 1.00),
    ('a0000000-0000-0000-0000-000000000001', 'ZONE_3', 'ZONE_2', 'LARGE',     0.01, 15.00, 130000, 11000, 5.00),
    ('a0000000-0000-0000-0000-000000000001', 'ZONE_3', 'ZONE_2', 'OVERSIZED', 0.01, 30.00, 210000, 16000, 15.00),

    -- Island-wide Outstation: ZONE_3 -> ZONE_3
    ('a0000000-0000-0000-0000-000000000001', 'ZONE_3', 'ZONE_3', 'SMALL',     0.01,  1.00,  40000,     0, 1.00),
    ('a0000000-0000-0000-0000-000000000001', 'ZONE_3', 'ZONE_3', 'MEDIUM',    0.01,  5.00,  65000,  7500, 1.00),
    ('a0000000-0000-0000-0000-000000000001', 'ZONE_3', 'ZONE_3', 'LARGE',     0.01, 15.00, 105000,  9500, 5.00),
    ('a0000000-0000-0000-0000-000000000001', 'ZONE_3', 'ZONE_3', 'OVERSIZED', 0.01, 30.00, 180000, 14000, 15.00)
ON CONFLICT (rate_version_id, pickup_zone_code, dropoff_zone_code, size_category, min_weight_kg, max_weight_kg)
DO NOTHING;
