-- Merchant bookings stored the weight only inside quote_snapshot (re-audit B1).
-- Copy it into weight_kg so dashboards and dispatch see the real value.
UPDATE parcels
SET weight_kg = (quote_snapshot ->> 'weightKg')::numeric
WHERE weight_kg IS NULL
  AND quote_snapshot IS NOT NULL
  AND jsonb_typeof(quote_snapshot -> 'weightKg') = 'number';
