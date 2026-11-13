-- ============================================================================
-- SwiftDrop Identity Provisioning: swiftdrop-identity
-- Target: keycloak_db and keycloak role
-- Run with: psql -X -v ON_ERROR_STOP=1 against swiftdrop-identity postgres DB.
-- Do not run inside a transaction block.
-- ============================================================================
\set ON_ERROR_STOP on

-- Step 0: Safe pre-cleanup of any lingering SET/INHERIT delegation from a prior interrupted run
DO $$
DECLARE
    v_role_oid oid := to_regrole('keycloak');
    v_admin_oid oid := to_regrole('postgres');
BEGIN
    IF v_role_oid IS NOT NULL AND v_admin_oid IS NOT NULL THEN
        IF EXISTS (
            SELECT 1 FROM pg_auth_members
            WHERE member = v_admin_oid
              AND roleid = v_role_oid
              AND (set_option OR inherit_option)
        ) THEN
            REVOKE SET OPTION FOR keycloak FROM postgres;
            REVOKE INHERIT OPTION FOR keycloak FROM postgres;
            RAISE NOTICE 'Cleaned up lingering SET/INHERIT delegation on keycloak from prior session.';
        END IF;
    END IF;
END
$$;

-- Step 1: Create dedicated unprivileged role if it does not exist
SELECT (to_regrole('keycloak') IS NULL) AS new_role \gset
\if :new_role
SELECT format('CREATE ROLE %I LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS', 'keycloak') \gexec
\getenv keycloak_pass KEYCLOAK_DB_PASSWORD
\if :{?keycloak_pass}
SELECT format('ALTER ROLE %I WITH PASSWORD %L', 'keycloak', :'keycloak_pass') \gexec
\unset keycloak_pass
\echo keycloak password applied securely from environment.
\else
\echo Prompting for keycloak password:
\password keycloak
\endif
\else
\echo Role keycloak already exists; preserving existing role attributes and password.
\endif

-- Step 2: Create independent logical database owned by keycloak
SELECT NOT EXISTS (SELECT FROM pg_database WHERE datname = 'keycloak_db') AS new_database \gset
\if :new_database
GRANT keycloak TO postgres WITH SET TRUE, INHERIT FALSE;
SELECT format('CREATE DATABASE %I OWNER %I', 'keycloak_db', 'keycloak') \gexec

SET ROLE keycloak;
REVOKE ALL ON DATABASE keycloak_db FROM PUBLIC;
GRANT CONNECT ON DATABASE keycloak_db TO postgres, keycloak;
RESET ROLE;

-- Step 3: Configure schema-level permissions inside keycloak_db
\connect keycloak_db
SET ROLE keycloak;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO keycloak;
RESET ROLE;

REVOKE SET OPTION FOR keycloak FROM postgres;
\connect postgres

\else
\echo Database keycloak_db already exists; verifying ownership and grants...
GRANT keycloak TO postgres WITH SET TRUE, INHERIT FALSE;
SET ROLE keycloak;
REVOKE ALL ON DATABASE keycloak_db FROM PUBLIC;
GRANT CONNECT ON DATABASE keycloak_db TO postgres, keycloak;
RESET ROLE;

\connect keycloak_db
SET ROLE keycloak;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO keycloak;
RESET ROLE;
REVOKE SET OPTION FOR keycloak FROM postgres;
\connect postgres
\endif

-- Step 4: Strict assertion — failed delegation cleanup must raise a PostgreSQL exception
DO $$
DECLARE
    v_role_oid oid := to_regrole('keycloak');
    v_admin_oid oid := to_regrole('postgres');
    v_has_set_or_inherit boolean;
BEGIN
    IF v_role_oid IS NULL THEN
        RAISE EXCEPTION 'Assertion failed: keycloak role does not exist.';
    END IF;

    SELECT EXISTS (
        SELECT 1 FROM pg_auth_members
        WHERE member = v_admin_oid
          AND roleid = v_role_oid
          AND (set_option OR inherit_option)
    ) INTO v_has_set_or_inherit;

    IF v_has_set_or_inherit THEN
        RAISE EXCEPTION 'Security assertion failed: postgres possesses lingering SET or INHERIT delegation on keycloak. Run separate cleanup transaction.';
    END IF;

    RAISE NOTICE 'VERIFIED: postgres has NO effective SET or INHERIT access to keycloak.';
END
$$;
