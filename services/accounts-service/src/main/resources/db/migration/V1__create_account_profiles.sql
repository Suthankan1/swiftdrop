CREATE TABLE account_profiles (
    id UUID PRIMARY KEY,
    keycloak_sub VARCHAR(255) NOT NULL,
    email VARCHAR(320) NOT NULL,
    full_name VARCHAR(255) NOT NULL,
    phone_number VARCHAR(50) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_account_profiles_keycloak_sub UNIQUE (keycloak_sub),
    CONSTRAINT ck_account_profiles_subject_not_blank CHECK (keycloak_sub ~ '[^[:space:]]')
);

-- Identity binding and Accounts-generated identity cannot be reassigned by UPDATE.
CREATE FUNCTION prevent_account_identity_change() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.keycloak_sub IS DISTINCT FROM OLD.keycloak_sub
       OR NEW.id IS DISTINCT FROM OLD.id THEN
        RAISE EXCEPTION 'Account identity binding is immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER account_identity_immutable
BEFORE UPDATE ON account_profiles
FOR EACH ROW EXECUTE FUNCTION prevent_account_identity_change();
