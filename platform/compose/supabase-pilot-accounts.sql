-- ============================================================================
-- SwiftDrop Pilot Provisioning: swiftdrop-accounts
-- Target: accounts_db and accounts_app on swiftdrop-accounts project
-- Run with: psql -X -v ON_ERROR_STOP=1 against swiftdrop-accounts postgres DB.
-- Do not run inside a transaction block.
--
-- PostgreSQL 17 Role-Grant Semantics:
-- - The non-superuser CREATEROLE role (postgres) automatically receives an ADMIN
--   membership in accounts_app upon creation (admin_option=true, inherit_option=false,
--   set_option=false). This bootstrap-granted ADMIN record is preserved.
-- - Temporary administrative delegation uses a narrowly scoped SET TRUE grant:
--   GRANT accounts_app TO postgres WITH SET TRUE, INHERIT FALSE;
-- - Database-level grants and schema-level grants are executed under a correctly
--   scoped SET ROLE accounts_app context (the database owner).
-- - RESET ROLE is always executed before reconnecting or cleanup.
-- - Cleanup revokes ONLY the temporary delegation via:
--   REVOKE SET OPTION FOR accounts_app FROM postgres;
-- - Final assertion verifies that postgres has NO effective SET or INHERIT access,
--   raising a PostgreSQL exception and exiting non-zero if lingering delegation is detected.
-- ============================================================================
\set ON_ERROR_STOP on

-- Step 0: Safe pre-cleanup of any lingering SET/INHERIT delegation from a prior interrupted run
DO $$
DECLARE
    v_role_oid oid := to_regrole('accounts_app');
    v_admin_oid oid := to_regrole('postgres');
BEGIN
    IF v_role_oid IS NOT NULL AND v_admin_oid IS NOT NULL THEN
        IF EXISTS (
            SELECT 1 FROM pg_auth_members
            WHERE member = v_admin_oid
              AND roleid = v_role_oid
              AND (set_option OR inherit_option)
        ) THEN
            REVOKE SET OPTION FOR accounts_app FROM postgres;
            REVOKE INHERIT OPTION FOR accounts_app FROM postgres;
            RAISE NOTICE 'Cleaned up lingering SET/INHERIT delegation on accounts_app from prior session.';
        END IF;
    END IF;
END
$$;

-- Step 1: Create dedicated unprivileged role if it does not exist
SELECT (to_regrole('accounts_app') IS NULL) AS new_role \gset
\if :new_role
SELECT format('CREATE ROLE %I LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS', 'accounts_app') \gexec

-- Dual-mode password handling: automated via \getenv if in environment, else interactive \password:
\getenv accounts_pass ACCOUNTS_DB_PASSWORD
\if :{?accounts_pass}
SELECT format('ALTER ROLE %I WITH PASSWORD %L', 'accounts_app', :'accounts_pass') \gexec
\unset accounts_pass
\echo Password applied securely from environment.
\else
\echo Prompting for accounts_app password:
\password accounts_app
\endif

\else
\echo Role accounts_app already exists; preserving existing role attributes and password.
\endif

-- Step 2: Create independent logical database owned by accounts_app
SELECT NOT EXISTS (SELECT FROM pg_database WHERE datname = 'accounts_db') AS new_database \gset
\if :new_database
-- Narrowly scoped temporary SET TRUE delegation to satisfy non-superuser CREATEDB OWNER requirement
GRANT accounts_app TO postgres WITH SET TRUE, INHERIT FALSE;
SELECT format('CREATE DATABASE %I OWNER %I', 'accounts_db', 'accounts_app') \gexec

-- Configure database-level connect isolation under SET ROLE accounts_app (the database owner)
SET ROLE accounts_app;
REVOKE ALL ON DATABASE accounts_db FROM PUBLIC;
GRANT CONNECT ON DATABASE accounts_db TO postgres, accounts_app;
RESET ROLE;

-- Step 3: Configure schema-level permissions inside accounts_db
\connect accounts_db
-- Use temporary SET ROLE to configure public schema as pg_database_owner
SET ROLE accounts_app;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO accounts_app;
RESET ROLE;

-- Revoke temporary SET delegation immediately in its own command
REVOKE SET OPTION FOR accounts_app FROM postgres;
\connect postgres

\else
\echo Database accounts_db already exists; verifying ownership and grants...
-- If database already exists, ensure grants and isolation are verified
GRANT accounts_app TO postgres WITH SET TRUE, INHERIT FALSE;
SET ROLE accounts_app;
REVOKE ALL ON DATABASE accounts_db FROM PUBLIC;
GRANT CONNECT ON DATABASE accounts_db TO postgres, accounts_app;
RESET ROLE;

\connect accounts_db
SET ROLE accounts_app;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO accounts_app;
RESET ROLE;
REVOKE SET OPTION FOR accounts_app FROM postgres;
\connect postgres
\endif

-- Step 4: Strict assertion — failed delegation cleanup must raise a PostgreSQL exception
DO $$
DECLARE
    v_role_oid oid := to_regrole('accounts_app');
    v_admin_oid oid := to_regrole('postgres');
    v_has_set_or_inherit boolean;
BEGIN
    IF v_role_oid IS NULL THEN
        RAISE EXCEPTION 'Assertion failed: accounts_app role does not exist.';
    END IF;

    SELECT EXISTS (
        SELECT 1 FROM pg_auth_members
        WHERE member = v_admin_oid
          AND roleid = v_role_oid
          AND (set_option OR inherit_option)
    ) INTO v_has_set_or_inherit;

    IF v_has_set_or_inherit THEN
        RAISE EXCEPTION 'Security assertion failed: postgres possesses lingering SET or INHERIT delegation on accounts_app. Run separate cleanup transaction.';
    END IF;

    RAISE NOTICE 'VERIFIED: postgres has NO effective SET or INHERIT access to accounts_app.';
END
$$;
