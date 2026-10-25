CREATE TABLE merchant_api_keys (
    id UUID PRIMARY KEY,
    merchant_id UUID NOT NULL REFERENCES merchants(id),
    name VARCHAR(255) NOT NULL,
    key_prefix VARCHAR(32) NOT NULL,
    key_digest VARCHAR(255) NOT NULL,
    scopes VARCHAR(255) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked_at TIMESTAMP WITH TIME ZONE,
    is_revoked BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT uq_merchant_api_keys_digest UNIQUE (key_digest),
    CONSTRAINT ck_merchant_api_keys_name_not_blank CHECK (name ~ '[^[:space:]]'),
    CONSTRAINT ck_merchant_api_keys_prefix_not_blank CHECK (key_prefix ~ '[^[:space:]]'),
    CONSTRAINT ck_merchant_api_keys_digest_not_blank CHECK (key_digest ~ '[^[:space:]]'),
    CONSTRAINT ck_merchant_api_keys_scopes_not_blank CHECK (scopes ~ '[^[:space:]]'),
    CONSTRAINT ck_merchant_api_keys_expiry CHECK (expires_at > created_at)
);

CREATE INDEX idx_merchant_api_keys_merchant_id ON merchant_api_keys(merchant_id);
CREATE INDEX idx_merchant_api_keys_prefix ON merchant_api_keys(key_prefix);

CREATE FUNCTION prevent_merchant_api_key_identity_change() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id
       OR NEW.merchant_id IS DISTINCT FROM OLD.merchant_id
       OR NEW.key_digest IS DISTINCT FROM OLD.key_digest
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'Merchant API key identity and cryptographic digest are immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER merchant_api_key_immutable
BEFORE UPDATE ON merchant_api_keys
FOR EACH ROW EXECUTE FUNCTION prevent_merchant_api_key_identity_change();
