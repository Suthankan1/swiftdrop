ALTER TABLE staff_profiles
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

CREATE INDEX idx_staff_profiles_admin_listing
    ON staff_profiles (created_at ASC, id ASC);

CREATE INDEX idx_staff_profiles_admin_filters
    ON staff_profiles (staff_role, status, assigned_zone, created_at ASC, id ASC);
