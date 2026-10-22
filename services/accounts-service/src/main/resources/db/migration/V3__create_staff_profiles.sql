CREATE TABLE staff_profiles (
    id UUID PRIMARY KEY,
    account_profile_id UUID REFERENCES account_profiles(id),
    keycloak_sub VARCHAR(255) NOT NULL,
    staff_id VARCHAR(50) NOT NULL,
    staff_role VARCHAR(30) NOT NULL,
    department VARCHAR(100) NOT NULL,
    assigned_zone VARCHAR(100),
    status VARCHAR(30) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_staff_profiles_keycloak_sub UNIQUE (keycloak_sub),
    CONSTRAINT uq_staff_profiles_staff_id UNIQUE (staff_id),
    CONSTRAINT ck_staff_profiles_role CHECK (staff_role IN ('COURIER', 'DISPATCHER', 'ADMIN')),
    CONSTRAINT ck_staff_profiles_status CHECK (status IN ('ACTIVE', 'SUSPENDED', 'INACTIVE')),
    CONSTRAINT ck_staff_profiles_subject_not_blank CHECK (keycloak_sub ~ '[^[:space:]]'),
    CONSTRAINT ck_staff_profiles_staff_id_not_blank CHECK (staff_id ~ '[^[:space:]]'),
    CONSTRAINT ck_staff_profiles_department_not_blank CHECK (department ~ '[^[:space:]]')
);

CREATE INDEX idx_staff_profiles_account_profile_id ON staff_profiles(account_profile_id);

CREATE FUNCTION prevent_staff_identity_change() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.keycloak_sub IS DISTINCT FROM OLD.keycloak_sub
       OR NEW.id IS DISTINCT FROM OLD.id THEN
        RAISE EXCEPTION 'Staff identity binding is immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER staff_identity_immutable
BEFORE UPDATE ON staff_profiles
FOR EACH ROW EXECUTE FUNCTION prevent_staff_identity_change();
