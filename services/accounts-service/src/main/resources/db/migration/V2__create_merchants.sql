CREATE TABLE merchants (
    id UUID PRIMARY KEY,
    account_profile_id UUID NOT NULL REFERENCES account_profiles(id),
    keycloak_sub VARCHAR(255) NOT NULL,
    business_name VARCHAR(255) NOT NULL,
    registration_number VARCHAR(100) NOT NULL,
    status VARCHAR(30) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_merchants_keycloak_sub UNIQUE (keycloak_sub),
    CONSTRAINT uq_merchants_registration_number UNIQUE (registration_number),
    CONSTRAINT ck_merchants_status CHECK (status IN ('PENDING', 'APPROVED', 'SUSPENDED', 'REJECTED')),
    CONSTRAINT ck_merchants_subject_not_blank CHECK (keycloak_sub ~ '[^[:space:]]'),
    CONSTRAINT ck_merchants_business_name_not_blank CHECK (business_name ~ '[^[:space:]]')
);

CREATE INDEX idx_merchants_account_profile_id ON merchants(account_profile_id);

CREATE FUNCTION prevent_merchant_identity_change() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.keycloak_sub IS DISTINCT FROM OLD.keycloak_sub
       OR NEW.id IS DISTINCT FROM OLD.id
       OR NEW.account_profile_id IS DISTINCT FROM OLD.account_profile_id THEN
        RAISE EXCEPTION 'Merchant identity and tenant binding are immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER merchant_identity_immutable
BEFORE UPDATE ON merchants
FOR EACH ROW EXECUTE FUNCTION prevent_merchant_identity_change();
