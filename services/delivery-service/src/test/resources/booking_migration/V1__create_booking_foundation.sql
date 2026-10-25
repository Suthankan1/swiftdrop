CREATE TABLE parcels (
 id UUID PRIMARY KEY,
 tracking_number VARCHAR(21) NOT NULL UNIQUE CHECK (tracking_number ~ '^SWD-[0-9]{8}-[A-Z0-9]{4,8}$'),
 created_by_sub VARCHAR(255) NOT NULL CHECK (created_by_sub ~ '[^[:space:]]'),
 quote_id UUID NOT NULL UNIQUE,
 current_status VARCHAR(30) NOT NULL CHECK (current_status = 'CREATED'),
 version BIGINT NOT NULL CHECK (version = 1),
 pickup_address VARCHAR(2000) NOT NULL CHECK (pickup_address ~ '[^[:space:]]'),
 dropoff_address VARCHAR(2000) NOT NULL CHECK (dropoff_address ~ '[^[:space:]]'),
 recipient_name VARCHAR(255) NOT NULL CHECK (recipient_name ~ '[^[:space:]]'),
 recipient_phone VARCHAR(50) NOT NULL CHECK (recipient_phone ~ '[^[:space:]]'),
 delivery_notes VARCHAR(2000),
 amount_minor_units BIGINT NOT NULL CHECK (amount_minor_units >= 0),
 currency CHAR(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
 rate_version TEXT NOT NULL CHECK (rate_version <> ''),
 quote_snapshot JSONB NOT NULL CHECK (jsonb_typeof(quote_snapshot) = 'object' AND quote_snapshot ?& ARRAY['quoteId','verifiedAmountMinorUnits','currency','rateVersion','expiresAt','isValid','validationCode']),
 created_at TIMESTAMPTZ NOT NULL,
 CHECK ((quote_snapshot->>'quoteId' = quote_id::text) IS TRUE),
 CHECK (((quote_snapshot->>'verifiedAmountMinorUnits')::bigint = amount_minor_units) IS TRUE),
 CHECK ((quote_snapshot->>'currency' = currency) IS TRUE),
 CHECK ((quote_snapshot->>'rateVersion' = rate_version) IS TRUE),
 CHECK ((quote_snapshot->>'isValid' = 'true' AND quote_snapshot->>'validationCode' = 'VALID') IS TRUE),
 CHECK (((quote_snapshot->>'expiresAt')::timestamptz > created_at) IS TRUE),
 UNIQUE (id, created_by_sub)
);
CREATE INDEX parcels_creator_created ON parcels (created_by_sub, created_at);
CREATE TABLE status_history (
 id UUID PRIMARY KEY,
 parcel_id UUID NOT NULL REFERENCES parcels(id),
 state_version BIGINT NOT NULL CHECK (state_version = 1),
 old_status VARCHAR(30) CHECK (old_status IS NULL),
 new_status VARCHAR(30) NOT NULL CHECK (new_status = 'CREATED'),
 occurred_at TIMESTAMPTZ NOT NULL,
 UNIQUE (parcel_id, state_version)
);
CREATE TABLE outbox_events (
 event_id UUID PRIMARY KEY,
 parcel_id UUID NOT NULL REFERENCES parcels(id),
 aggregate_version BIGINT NOT NULL CHECK (aggregate_version = 1),
 event_type TEXT NOT NULL CHECK (event_type = 'parcel.status.changed.v1'),
 payload JSONB NOT NULL,
 created_at TIMESTAMPTZ NOT NULL,
 published_at TIMESTAMPTZ,
 UNIQUE (parcel_id, aggregate_version)
);
CREATE INDEX outbox_pending ON outbox_events(created_at) WHERE published_at IS NULL;
CREATE TABLE idempotency_records (
 actor_sub VARCHAR(255) NOT NULL,
 idempotency_key UUID NOT NULL,
 request_digest CHAR(64) NOT NULL CHECK (request_digest ~ '^[a-f0-9]{64}$'),
 parcel_id UUID NOT NULL UNIQUE,
 response_json JSONB NOT NULL,
 created_at TIMESTAMPTZ NOT NULL,
 PRIMARY KEY (actor_sub, idempotency_key),
 FOREIGN KEY (parcel_id, actor_sub) REFERENCES parcels(id, created_by_sub)
);
CREATE FUNCTION preserve_booking_provenance() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF (NEW.id,NEW.quote_id,NEW.created_by_sub,NEW.quote_snapshot,NEW.amount_minor_units,NEW.currency,NEW.rate_version,NEW.created_at,NEW.tracking_number)
 IS DISTINCT FROM
 (OLD.id,OLD.quote_id,OLD.created_by_sub,OLD.quote_snapshot,OLD.amount_minor_units,OLD.currency,OLD.rate_version,OLD.created_at,OLD.tracking_number) THEN
  RAISE EXCEPTION 'Booking provenance is immutable' USING ERRCODE = '23514';
 END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER immutable_booking_provenance BEFORE UPDATE ON parcels FOR EACH ROW EXECUTE FUNCTION preserve_booking_provenance();
CREATE FUNCTION reject_audit_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Creation audit records are immutable' USING ERRCODE = '23514'; END; $$;
CREATE TRIGGER immutable_status_history BEFORE UPDATE OR DELETE ON status_history FOR EACH ROW EXECUTE FUNCTION reject_audit_mutation();
CREATE TRIGGER immutable_idempotency BEFORE UPDATE OR DELETE ON idempotency_records FOR EACH ROW EXECUTE FUNCTION reject_audit_mutation();
