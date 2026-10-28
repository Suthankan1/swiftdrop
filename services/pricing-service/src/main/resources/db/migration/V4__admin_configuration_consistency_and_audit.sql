-- Pricing-owned additive migration. Preserve V1-V3 and all historical rows.
ALTER TABLE pricing_zones ADD COLUMN revision BIGINT NOT NULL DEFAULT 0 CHECK (revision >= 0);
ALTER TABLE pricing_rate_versions ADD COLUMN revision BIGINT NOT NULL DEFAULT 0 CHECK (revision >= 0);

CREATE TABLE pricing_configuration_lock (
    id SMALLINT PRIMARY KEY CHECK (id = 1)
);
INSERT INTO pricing_configuration_lock (id) VALUES (1);

CREATE TABLE pricing_admin_audit (
    id UUID PRIMARY KEY,
    actor_sub VARCHAR(255) NOT NULL CHECK (length(trim(actor_sub)) > 0),
    operation VARCHAR(64) NOT NULL,
    resource_type VARCHAR(32) NOT NULL,
    resource_id UUID NOT NULL,
    rule_id UUID,
    previous_status VARCHAR(32),
    new_status VARCHAR(32),
    previous_revision BIGINT,
    new_revision BIGINT NOT NULL,
    previous_state TEXT,
    new_state TEXT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    request_id UUID NOT NULL
);
CREATE INDEX idx_pricing_admin_audit_resource ON pricing_admin_audit (resource_id, occurred_at);

-- Serialize sibling rule writes even for direct SQL, and protect published rules.
CREATE FUNCTION guard_pricing_rate_rule() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE card_status VARCHAR(32);
BEGIN
    IF TG_OP = 'UPDATE' AND NEW.rate_version_id <> OLD.rate_version_id THEN
        RAISE EXCEPTION 'Rate rule parent is immutable' USING ERRCODE = '23514';
    END IF;
    SELECT status INTO card_status FROM pricing_rate_versions
      WHERE id = CASE WHEN TG_OP = 'DELETE' THEN OLD.rate_version_id ELSE NEW.rate_version_id END
      FOR UPDATE;
    IF card_status IS DISTINCT FROM 'DRAFT' THEN
        RAISE EXCEPTION 'Published rate rules are immutable' USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    IF EXISTS (
        SELECT 1 FROM pricing_rate_rules r
        WHERE r.rate_version_id = NEW.rate_version_id AND r.id <> NEW.id
          AND r.pickup_zone_code = NEW.pickup_zone_code
          AND r.dropoff_zone_code = NEW.dropoff_zone_code
          AND r.size_category = NEW.size_category
          AND numrange(r.min_weight_kg, r.max_weight_kg, '[]')
              && numrange(NEW.min_weight_kg, NEW.max_weight_kg, '[]')
    ) THEN
        RAISE EXCEPTION 'Overlapping rate rule band' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER pricing_rate_rule_guard BEFORE INSERT OR UPDATE OR DELETE
    ON pricing_rate_rules FOR EACH ROW EXECUTE FUNCTION guard_pricing_rate_rule();

CREATE FUNCTION guard_pricing_rate_version() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status <> 'DRAFT' AND
       (NEW.version_code, NEW.currency, NEW.effective_from, NEW.description, NEW.created_at)
       IS DISTINCT FROM
       (OLD.version_code, OLD.currency, OLD.effective_from, OLD.description, OLD.created_at) THEN
        RAISE EXCEPTION 'Published rate card metadata is immutable' USING ERRCODE = '23514';
    END IF;
    IF OLD.status <> 'DRAFT' AND (
        NEW.activated_at IS DISTINCT FROM OLD.activated_at OR
        (NEW.effective_to IS DISTINCT FROM OLD.effective_to AND NOT
            (OLD.status = 'ACTIVE' AND NEW.status = 'SUPERSEDED')) OR
        (NEW.archived_at IS DISTINCT FROM OLD.archived_at AND NOT
            (OLD.status = 'SUPERSEDED' AND NEW.status = 'ARCHIVED'))
    ) THEN
        RAISE EXCEPTION 'Published rate card audit/effective metadata is immutable' USING ERRCODE = '23514';
    END IF;
    IF NEW.status <> OLD.status AND NOT (
        (OLD.status = 'DRAFT' AND NEW.status = 'ACTIVE') OR
        (OLD.status = 'ACTIVE' AND NEW.status = 'SUPERSEDED') OR
        (OLD.status = 'SUPERSEDED' AND NEW.status = 'ARCHIVED')
    ) THEN
        RAISE EXCEPTION 'Invalid rate card lifecycle transition' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER pricing_rate_version_guard BEFORE UPDATE ON pricing_rate_versions
    FOR EACH ROW EXECUTE FUNCTION guard_pricing_rate_version();
