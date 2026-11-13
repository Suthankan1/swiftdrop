-- ============================================================================
-- SwiftDrop Business Provisioning: swiftdrop-business
-- Target: pricing_db, delivery_db, communications_db and dedicated roles
-- Run with: psql -X -v ON_ERROR_STOP=1 against swiftdrop-business postgres DB.
-- Do not run inside a transaction block.
-- ============================================================================
\set ON_ERROR_STOP on

-- Step 0: Safe pre-cleanup of any lingering SET/INHERIT delegation from a prior interrupted run
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
                RAISE NOTICE 'Cleaned up lingering SET/INHERIT delegation on % from prior session.', r;
            END IF;
        END IF;
    END LOOP;
END
$$;

-- Step 1: Create dedicated unprivileged roles if they do not exist
SELECT (to_regrole('pricing_app') IS NULL) AS new_pricing_role \gset
\if :new_pricing_role
SELECT format('CREATE ROLE %I LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS', 'pricing_app') \gexec
\getenv pricing_pass PRICING_DB_PASSWORD
\if :{?pricing_pass}
SELECT format('ALTER ROLE %I WITH PASSWORD %L', 'pricing_app', :'pricing_pass') \gexec
\unset pricing_pass
\echo pricing_app password applied securely from environment.
\else
\echo Prompting for pricing_app password:
\password pricing_app
\endif
\else
\echo Role pricing_app already exists; preserving existing role attributes and password.
\endif

SELECT (to_regrole('delivery_app') IS NULL) AS new_delivery_role \gset
\if :new_delivery_role
SELECT format('CREATE ROLE %I LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS', 'delivery_app') \gexec
\getenv delivery_pass DELIVERY_DB_PASSWORD
\if :{?delivery_pass}
SELECT format('ALTER ROLE %I WITH PASSWORD %L', 'delivery_app', :'delivery_pass') \gexec
\unset delivery_pass
\echo delivery_app password applied securely from environment.
\else
\echo Prompting for delivery_app password:
\password delivery_app
\endif
\else
\echo Role delivery_app already exists; preserving existing role attributes and password.
\endif

SELECT (to_regrole('communications_app') IS NULL) AS new_comm_role \gset
\if :new_comm_role
SELECT format('CREATE ROLE %I LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS', 'communications_app') \gexec
\getenv communications_pass COMMUNICATIONS_DB_PASSWORD
\if :{?communications_pass}
SELECT format('ALTER ROLE %I WITH PASSWORD %L', 'communications_app', :'communications_pass') \gexec
\unset communications_pass
\echo communications_app password applied securely from environment.
\else
\echo Prompting for communications_app password:
\password communications_app
\endif
\else
\echo Role communications_app already exists; preserving existing role attributes and password.
\endif

-- Step 2: Create pricing_db
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
\echo Database pricing_db already exists; verifying ownership and grants...
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

-- Step 3: Create delivery_db
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
\echo Database delivery_db already exists; verifying ownership and grants...
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

-- Step 4: Create communications_db
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
\echo Database communications_db already exists; verifying ownership and grants...
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

-- Step 5: Verify that postgres has no unintended effective SET/INHERIT access across business roles
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
            RAISE EXCEPTION 'Assertion failed: business role % does not exist.', r;
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

-- Step 6: Read-only isolation matrix: own CONNECT must be true, cross-service CONNECT false
SELECT r.role_name, d.database_name,
       has_database_privilege(r.role_name, d.database_name, 'CONNECT') AS can_connect
FROM (VALUES ('pricing_app'), ('delivery_app'), ('communications_app')) AS r(role_name)
CROSS JOIN (VALUES ('pricing_db'), ('delivery_db'), ('communications_db')) AS d(database_name)
ORDER BY r.role_name, d.database_name;
