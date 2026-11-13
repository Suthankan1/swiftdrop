-- REVIEW DRAFT ONLY. No helper executes this file.
-- This first command makes accidental execution inert. After explicit approval,
-- extract ONE project section into a separate reviewed script and run it with
-- psql -X -v ON_ERROR_STOP=1 against that project's postgres maintenance DB.
-- Supply host/user separately and enter the admin password at psql's prompt.
-- Do not use a password-bearing command-line URL or enable psql echo tracing.
-- CREATE DATABASE must run outside a transaction.
\quit

-- Preconditions for EACH section:
-- 1. Confirm the target project and inspect existing roles/DBs first.
-- 2. Existing roles must be unprivileged, with no inherited privileged or
--    cross-service memberships. Existing objects/role attributes are untouched.
-- 3. In PostgreSQL 17 on Supabase, 'postgres' is NOSUPERUSER (is_superuser: false)
--    with CREATEDB and CREATEROLE. Creating a role automatically grants postgres
--    an ADMIN membership (admin_option=true, inherit_option=false, set_option=false).
--    This bootstrap-granted ADMIN record is preserved and not revoked.
-- 4. Temporary administrative delegation uses narrowly scoped SET TRUE grants:
--    GRANT <role> TO postgres WITH SET TRUE, INHERIT FALSE;
-- 5. Database-level and schema-level grants are executed under a correctly scoped
--    SET ROLE <role> context (as the database/schema owner).
-- 6. RESET ROLE is always executed before reconnecting or cleanup.
-- 7. Cleanup revokes ONLY the temporary delegation via:
--    REVOKE SET OPTION FOR <role> FROM postgres;
--    Final assertions verify that postgres has NO effective SET or INHERIT access,
--    raising a PostgreSQL exception and failing fast if lingering delegation is detected.
-- 8. Safe catalog lookup functions (to_regrole) prevent casting errors when roles
--    do not yet exist.
-- 9. Interactive password prompts use psql's native \password command, which
--    hashes the password client-side without terminal history or command logging.
-- 10. New databases are empty: approved Flyway/Keycloak initialization is a
--    separate operation, NEVER part of database creation.

-- ============================================================================
-- PROJECT: swiftdrop-accounts (connect as project admin to postgres)
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

-- 1. Create dedicated unprivileged role if it does not exist
SELECT (to_regrole('accounts_app') IS NULL) AS new_role \gset
\if :new_role
SELECT format('CREATE ROLE %I LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS', 'accounts_app') \gexec
\echo Prompting for accounts_app password (interactive client-side hash, no logging):
\password accounts_app
\else
\echo Role accounts_app already exists; preserving existing role attributes and password.
\endif

-- 2. Create independent logical database owned by accounts_app
SELECT NOT EXISTS (SELECT FROM pg_database WHERE datname = 'accounts_db') AS new_database \gset
\if :new_database
GRANT accounts_app TO postgres WITH SET TRUE, INHERIT FALSE;
SELECT format('CREATE DATABASE %I OWNER %I', 'accounts_db', 'accounts_app') \gexec

SET ROLE accounts_app;
REVOKE ALL ON DATABASE accounts_db FROM PUBLIC;
GRANT CONNECT ON DATABASE accounts_db TO postgres, accounts_app;
RESET ROLE;

-- 3. Configure schema-level permissions inside accounts_db
\connect accounts_db
SET ROLE accounts_app;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO accounts_app;
RESET ROLE;

REVOKE SET OPTION FOR accounts_app FROM postgres;
\connect postgres

\else
\echo Database accounts_db already exists; verifying ownership and grants...
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

-- 4. Strict assertion — failed delegation cleanup must raise a PostgreSQL exception
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

-- ============================================================================
-- PROJECT: swiftdrop-business (connect as project admin to postgres)
-- ============================================================================
\set ON_ERROR_STOP on

-- Ensure no stale temporary administrative delegations exist from prior runs
DO $$
DECLARE
    r text;
    v_role_oid oid;
    v_admin_oid oid := to_regrole('postgres');
BEGIN
    FOR r IN SELECT unnest(ARRAY['pricing_app', 'delivery_app', 'communications_app'])
    LOOP
        v_role_oid := to_regrole(r);
        IF v_role_oid IS NOT NULL AND v_admin_oid IS NOT NULL THEN
            IF EXISTS (
                SELECT 1 FROM pg_auth_members
                WHERE member = v_admin_oid
                  AND roleid = v_role_oid
                  AND (set_option OR inherit_option)
            ) THEN
                EXECUTE format('REVOKE SET OPTION FOR %I FROM postgres', r);
                EXECUTE format('REVOKE INHERIT OPTION FOR %I FROM postgres', r);
            END IF;
        END IF;
    END LOOP;
END
$$;

-- 1. Create dedicated unprivileged roles if they do not exist
SELECT (to_regrole('pricing_app') IS NULL) AS new_pricing_role \gset
\if :new_pricing_role
SELECT format('CREATE ROLE %I LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS', 'pricing_app') \gexec
\echo Prompting for pricing_app password:
\password pricing_app
\else
\echo pricing_app role already exists; preserving existing role attributes and password.
\endif

SELECT (to_regrole('delivery_app') IS NULL) AS new_delivery_role \gset
\if :new_delivery_role
SELECT format('CREATE ROLE %I LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS', 'delivery_app') \gexec
\echo Prompting for delivery_app password:
\password delivery_app
\else
\echo delivery_app role already exists; preserving existing role attributes and password.
\endif

SELECT (to_regrole('communications_app') IS NULL) AS new_comm_role \gset
\if :new_comm_role
SELECT format('CREATE ROLE %I LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS', 'communications_app') \gexec
\echo Prompting for communications_app password:
\password communications_app
\else
\echo communications_app role already exists; preserving existing role attributes and password.
\endif

-- 2. Create pricing_db
SELECT NOT EXISTS (SELECT FROM pg_database WHERE datname = 'pricing_db') AS new_pricing_db \gset
\if :new_pricing_db
GRANT pricing_app TO postgres WITH SET TRUE, INHERIT FALSE;
SELECT format('CREATE DATABASE %I OWNER %I', 'pricing_db', 'pricing_app') \gexec

SET ROLE pricing_app;
REVOKE ALL ON DATABASE pricing_db FROM PUBLIC, delivery_app, communications_app;
GRANT CONNECT ON DATABASE pricing_db TO postgres, pricing_app;
RESET ROLE;

\connect pricing_db
SET ROLE pricing_app;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO pricing_app;
RESET ROLE;

REVOKE SET OPTION FOR pricing_app FROM postgres;
\connect postgres
\else
\echo pricing_db already exists; verifying ownership and grants...
GRANT pricing_app TO postgres WITH SET TRUE, INHERIT FALSE;
SET ROLE pricing_app;
REVOKE ALL ON DATABASE pricing_db FROM PUBLIC, delivery_app, communications_app;
GRANT CONNECT ON DATABASE pricing_db TO postgres, pricing_app;
RESET ROLE;

\connect pricing_db
SET ROLE pricing_app;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO pricing_app;
RESET ROLE;
REVOKE SET OPTION FOR pricing_app FROM postgres;
\connect postgres
\endif

-- 3. Create delivery_db
SELECT NOT EXISTS (SELECT FROM pg_database WHERE datname = 'delivery_db') AS new_delivery_db \gset
\if :new_delivery_db
GRANT delivery_app TO postgres WITH SET TRUE, INHERIT FALSE;
SELECT format('CREATE DATABASE %I OWNER %I', 'delivery_db', 'delivery_app') \gexec

SET ROLE delivery_app;
REVOKE ALL ON DATABASE delivery_db FROM PUBLIC, pricing_app, communications_app;
GRANT CONNECT ON DATABASE delivery_db TO postgres, delivery_app;
RESET ROLE;

\connect delivery_db
SET ROLE delivery_app;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO delivery_app;
RESET ROLE;

REVOKE SET OPTION FOR delivery_app FROM postgres;
\connect postgres
\else
\echo delivery_db already exists; verifying ownership and grants...
GRANT delivery_app TO postgres WITH SET TRUE, INHERIT FALSE;
SET ROLE delivery_app;
REVOKE ALL ON DATABASE delivery_db FROM PUBLIC, pricing_app, communications_app;
GRANT CONNECT ON DATABASE delivery_db TO postgres, delivery_app;
RESET ROLE;

\connect delivery_db
SET ROLE delivery_app;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO delivery_app;
RESET ROLE;
REVOKE SET OPTION FOR delivery_app FROM postgres;
\connect postgres
\endif

-- 4. Create communications_db
SELECT NOT EXISTS (SELECT FROM pg_database WHERE datname = 'communications_db') AS new_comm_db \gset
\if :new_comm_db
GRANT communications_app TO postgres WITH SET TRUE, INHERIT FALSE;
SELECT format('CREATE DATABASE %I OWNER %I', 'communications_db', 'communications_app') \gexec

SET ROLE communications_app;
REVOKE ALL ON DATABASE communications_db FROM PUBLIC, pricing_app, delivery_app;
GRANT CONNECT ON DATABASE communications_db TO postgres, communications_app;
RESET ROLE;

\connect communications_db
SET ROLE communications_app;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO communications_app;
RESET ROLE;

REVOKE SET OPTION FOR communications_app FROM postgres;
\connect postgres
\else
\echo communications_db already exists; verifying ownership and grants...
GRANT communications_app TO postgres WITH SET TRUE, INHERIT FALSE;
SET ROLE communications_app;
REVOKE ALL ON DATABASE communications_db FROM PUBLIC, pricing_app, delivery_app;
GRANT CONNECT ON DATABASE communications_db TO postgres, communications_app;
RESET ROLE;

\connect communications_db
SET ROLE communications_app;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO communications_app;
RESET ROLE;
REVOKE SET OPTION FOR communications_app FROM postgres;
\connect postgres
\endif

-- 5. Verify that postgres has no unintended effective SET/INHERIT access across business roles
DO $$
DECLARE
    r text;
    v_role_oid oid;
    v_admin_oid oid := to_regrole('postgres');
    v_lingering boolean := false;
BEGIN
    FOR r IN SELECT unnest(ARRAY['pricing_app', 'delivery_app', 'communications_app'])
    LOOP
        v_role_oid := to_regrole(r);
        IF v_role_oid IS NULL THEN
            RAISE EXCEPTION 'Assertion failed: business role % does not exist', r;
        END IF;

        IF EXISTS (
            SELECT 1 FROM pg_auth_members
            WHERE member = v_admin_oid
              AND roleid = v_role_oid
              AND (set_option OR inherit_option)
        ) THEN
            v_lingering := true;
        END IF;
    END LOOP;

    IF v_lingering THEN
        RAISE EXCEPTION 'Security assertion failed: postgres possesses lingering SET or INHERIT delegation on business roles. Run separate cleanup transaction.';
    END IF;

    RAISE NOTICE 'VERIFIED: postgres has NO effective SET or INHERIT access to business roles.';
END
$$;

-- 6. Read-only isolation matrix: own CONNECT must be true, cross-service CONNECT false
SELECT r.role_name, d.database_name,
       has_database_privilege(r.role_name, d.database_name, 'CONNECT') AS can_connect
FROM (VALUES ('pricing_app'), ('delivery_app'), ('communications_app')) AS r(role_name)
CROSS JOIN (VALUES ('pricing_db'), ('delivery_db'), ('communications_db')) AS d(database_name)
ORDER BY r.role_name, d.database_name;

-- ============================================================================
-- PROJECT: swiftdrop-identity (connect as project admin to postgres)
-- ============================================================================
\set ON_ERROR_STOP on

-- Ensure no stale temporary administrative delegation exists from prior runs
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
        END IF;
    END IF;
END
$$;

-- 1. Create dedicated unprivileged role if it does not exist
SELECT (to_regrole('keycloak') IS NULL) AS new_role \gset
\if :new_role
SELECT format('CREATE ROLE %I LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS', 'keycloak') \gexec
\echo Prompting for keycloak password:
\password keycloak
\else
\echo keycloak role already exists; preserving existing role attributes and password.
\endif

-- 2. Create independent logical database owned by keycloak
SELECT NOT EXISTS (SELECT FROM pg_database WHERE datname = 'keycloak_db') AS new_database \gset
\if :new_database
GRANT keycloak TO postgres WITH SET TRUE, INHERIT FALSE;
SELECT format('CREATE DATABASE %I OWNER %I', 'keycloak_db', 'keycloak') \gexec

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
\else
\echo keycloak_db already exists; verifying ownership and grants...
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

-- 3. Strict assertion — failed delegation cleanup must raise a PostgreSQL exception
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
