#!/usr/bin/env python3
"""Secret-safe, read-only readiness gates for opt-in team PostgreSQL.

No dotenv dependency, shell sourcing, SQL mutation, or automatic migration.
"""

import argparse
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import stat
import subprocess
import sys
from urllib.parse import parse_qs, urlsplit


HERE = Path(__file__).resolve().parent
SERVICES = {
    "ACCOUNTS": ("ACCOUNTS", "accounts_app", "accounts_db"),
    "PRICING": ("BUSINESS", "pricing_app", "pricing_db"),
    "DELIVERY": ("BUSINESS", "delivery_app", "delivery_db"),
    "COMMUNICATIONS": ("BUSINESS", "communications_app", "communications_db"),
    "KEYCLOAK": ("IDENTITY", "keycloak", "keycloak_db"),
}
LOCAL_DATABASES = {"accounts-postgres", "business-postgres", "keycloak-postgres"}


class CheckError(Exception):
    """Messages contain names/status only; never raw subprocess diagnostics."""


def read_env(path):
    if os.name != "nt" and stat.S_IMODE(path.stat().st_mode) != 0o600:
        raise CheckError("Environment file must have permissions 600.")
    values = {}
    for number, line in enumerate(path.read_text().splitlines(), 1):
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("export "):
            line = line[7:]
        key, sep, value = line.partition("=")
        key = key.strip()
        if not sep or not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", key):
            raise CheckError(f"Invalid environment assignment on line {number}.")
        if key in values:
            raise CheckError(f"Duplicate environment variable: {key}.")
        try:
            # Dotenv inline comments start after whitespace. A password's # is literal.
            if not value.lstrip().startswith(("'", '"')):
                value = re.split(r"\s+#", value, maxsplit=1)[0].strip()
            else:
                value = " ".join(shlex.split(value, comments=True))
        except ValueError:
            raise CheckError(f"Invalid environment quoting on line {number}.") from None
        values[key] = value
    return values


def required(values, key):
    value = values.get(key, "")
    if not value or value.startswith("replace_with_"):
        raise CheckError(f"Configure {key} in the ignored environment file.")
    return value


def target(values, service):
    group, role, database = SERVICES[service]
    raw = required(values, service + "_DB_URL")
    try:
        url = urlsplit(raw.removeprefix("jdbc:"))
        valid = (
            raw.startswith("jdbc:postgresql://")
            and url.port == 5432
            and url.hostname == required(values, f"SUPABASE_{group}_HOST")
            and url.path == "/" + database
            and not url.username and not url.password and not url.fragment
            and parse_qs(url.query).get("sslmode") in (["require"], ["verify-full"])
            and not ({"password", "user", "options"} & parse_qs(url.query).keys())
        )
    except ValueError:
        valid = False
    if not valid:
        raise CheckError(f"{service}_DB_URL must target its named database on its project's session pooler with TLS and no embedded credentials.")
    return url, group, role, database


def postgres(values, host, database, username, password, sql, sslmode="require", sslrootcert=None):
    if not shutil.which("psql"):
        raise CheckError("Install PostgreSQL psql to perform read-only checks.")
    env = {k: v for k, v in os.environ.items() if not k.startswith("PG")}
    env.update(PGHOST=host, PGPORT="5432", PGDATABASE=database, PGUSER=username,
               PGPASSWORD=password, PGSSLMODE=sslmode, PGCONNECT_TIMEOUT="8",
               PGOPTIONS="-c default_transaction_read_only=on -c statement_timeout=5000",
               PGAPPNAME="swiftdrop-readonly-readiness", PSQL_HISTORY="/dev/null")
    if sslrootcert:
        env["PGSSLROOTCERT"] = sslrootcert
    try:
        result = subprocess.run(
            ["psql", "-X", "-w", "-A", "-t", "-v", "ON_ERROR_STOP=1", "-c", "\\conninfo", "-c", sql],
            env=env, capture_output=True, text=True, timeout=20,
        )
    except subprocess.TimeoutExpired:
        raise CheckError("Connection/query timed out.") from None
    if result.returncode:
        error = result.stderr.lower()
        for needle, message in (
            ("could not translate host name", "DNS lookup failed."),
            ("password authentication failed", "PostgreSQL password authentication failed."),
            ("tenant or user not found", "Pooler tenant/user was not found."),
            ("permission denied", "PostgreSQL permission denied."),
            ("does not exist", "Requested database or database object does not exist."),
            ("timeout", "Connection/query timed out."),
            ("connection refused", "TCP connection refused."),
            ("network is unreachable", "Network is unreachable."),
            ("operation not permitted", "Network access is blocked."),
            ("ssl", "TLS negotiation or verification failed."),
        ):
            if needle in error:
                raise CheckError(message)
        raise CheckError("PostgreSQL connection/query failed; raw diagnostics withheld to protect credentials.")
    try:
        lines = result.stdout.splitlines()
        json_line = next(line for line in reversed(lines) if line.startswith("{"))
        data = json.loads(json_line)
        # pg_stat_ssl describes the pooler-to-database hop, not client TLS.
        data["upstream_tls"] = data.get("tls")
        preamble = result.stdout[:result.stdout.rfind(json_line)]
        data["tls"] = bool(re.search(r"\bTLSv[0-9]+\.[0-9]+\b", preamble))
        return data
    except (ValueError, StopIteration):
        raise CheckError("Unexpected PostgreSQL readiness response.") from None


def inspect(values):
    """Use supplied admin accounts only for catalog discovery, never application startup."""
    ok = True
    for group in ("ACCOUNTS", "BUSINESS", "IDENTITY"):
        expected = [(s, r, d) for s, (g, r, d) in SERVICES.items() if g == group]
        databases = ",".join("'" + d + "'" for _, _, d in expected)
        roles = ",".join("'" + r + "'" for _, r, _ in expected)
        sql = f"""SELECT json_build_object(
          'tls', (SELECT ssl FROM pg_stat_ssl WHERE pid=pg_backend_pid()),
          'databases', (SELECT coalesce(json_agg(json_build_object('name',datname,
            'owner',pg_get_userbyid(datdba),'connect',datallowconn)), '[]')
            FROM pg_database WHERE datname IN ({databases})),
          'roles', (SELECT coalesce(json_agg(json_build_object('name',rolname,
            'login',rolcanlogin,'elevated',rolsuper OR rolcreatedb OR rolcreaterole
            OR rolreplication OR rolbypassrls)), '[]') FROM pg_roles WHERE rolname IN ({roles})));
        """
        try:
            result = postgres(values, required(values, f"SUPABASE_{group}_HOST"), "postgres",
                              required(values, f"SUPABASE_{group}_ADMIN_USERNAME"),
                              required(values, f"SUPABASE_{group}_ADMIN_PASSWORD"), sql)
            if result["tls"] is not True:
                raise CheckError("TLS could not be confirmed.")
            print(f"{group} project: authenticated with client-to-pooler TLS; catalog read only.")
            if not result.get("upstream_tls"):
                print("  pg_stat_ssl reports no TLS on the pooler-to-database hop; client TLS is independently confirmed.")
            dbs = {d["name"]: d for d in result["databases"]}
            found_roles = {r["name"]: r for r in result["roles"]}
            for _, role, database in expected:
                db = dbs.get(database)
                account = found_roles.get(role)
                if db is None:
                    print(f"  {database}: MISSING database.")
                    ok = False
                else:
                    isolated = db["owner"] == role and db["connect"]
                    print(f"  {database}: exists; dedicated ownership {'OK' if isolated else 'NEEDS REVIEW'}.")
                    ok &= bool(isolated)
                valid_role = bool(account and account["login"] and not account["elevated"])
                state = "MISSING role" if account is None else "unsuitable role"
                print(f"  {role}: {'login role present; direct attributes unprivileged (memberships still require check)' if valid_role else state}.")
                ok &= valid_role
        except CheckError as error:
            ok = False
            for _, _, database in expected:
                print(f"{database}: BLOCKED — {error}")
    return ok


def check(values):
    """Fail closed unless all five runtime roles pass TLS and ownership checks."""
    ok = True
    for service, (_, expected_role, database) in SERVICES.items():
        try:
            url, group, _, _ = target(values, service)
            username = required(values, service + "_DB_USERNAME")
            if not re.fullmatch(re.escape(expected_role) + r"\.[a-z0-9]+", username):
                raise CheckError(f"Issue dedicated {expected_role}.PROJECT_REF credentials; admin/unqualified usernames are forbidden.")
            admin = values.get(f"SUPABASE_{group}_ADMIN_USERNAME", "")
            project_ref = values.get(f"SUPABASE_{group}_PROJECT_REF") or admin.partition(".")[2]
            if not project_ref:
                raise CheckError(f"Configure SUPABASE_{group}_PROJECT_REF to verify project routing.")
            if username != expected_role + "." + project_ref:
                raise CheckError("Runtime role belongs to the wrong Supabase project.")
            password = required(values, service + "_DB_PASSWORD")
            other_dbs = ",".join("'" + d + "'" for _, _, d in SERVICES.values() if d != database)
            sql = f"""SELECT json_build_object(
              'database',current_database(),'role',current_user,'session_role',session_user,
              'tls',(SELECT ssl FROM pg_stat_ssl WHERE pid=pg_backend_pid()),
              'safe_role',NOT EXISTS(SELECT 1 FROM pg_roles r WHERE pg_has_role(current_user,r.oid,'MEMBER')
                AND (r.rolsuper OR r.rolcreatedb OR r.rolcreaterole OR r.rolreplication OR r.rolbypassrls
                  OR (r.rolname LIKE 'pg_%' AND r.rolname <> 'pg_database_owner')
                  OR (r.rolname IN ('accounts_app','pricing_app','delivery_app','communications_app','keycloak')
                    AND r.rolname <> current_user))),
              'owner',(SELECT pg_get_userbyid(datdba)=current_user FROM pg_database WHERE datname=current_database()),
              'connect',has_database_privilege(current_user,current_database(),'CONNECT'),
              'schema',(has_schema_privilege(current_user,'public','USAGE') AND has_schema_privilege(current_user,'public','CREATE')),
              'foreign_connect',EXISTS(SELECT 1 FROM pg_database WHERE datname IN ({other_dbs})
                AND (has_database_privilege(current_user,oid,'CONNECT')
                  OR pg_has_role(current_user,datdba,'MEMBER'))),
              'tables',NOT EXISTS(SELECT 1 FROM pg_tables WHERE schemaname='public'
                AND EXISTS(SELECT 1 FROM unnest(ARRAY['SELECT','INSERT','UPDATE','DELETE']) p WHERE NOT has_table_privilege(current_user,format('%I.%I',schemaname,tablename),p))),
              'sequences',NOT EXISTS(SELECT 1 FROM pg_sequences WHERE schemaname='public'
                AND EXISTS(SELECT 1 FROM unnest(ARRAY['USAGE','UPDATE']) p WHERE NOT has_sequence_privilege(current_user,format('%I.%I',schemaname,sequencename),p))),
              'history',to_regclass('public.{'databasechangelog' if service == 'KEYCLOAK' else 'flyway_schema_history'}') IS NOT NULL);
            """
            query = parse_qs(url.query)
            result = postgres(values, url.hostname, database, username, password, sql,
                              query["sslmode"][0], query.get("sslrootcert", [None])[0])
            failures = []
            if result["database"] != database or result["role"] != expected_role or result["session_role"] != expected_role:
                failures.append("database/role mapping")
            for field in ("tls", "safe_role", "owner", "connect", "schema", "tables", "sequences", "history"):
                if result[field] is not True:
                    failures.append(field)
            if result["foreign_connect"]:
                failures.append("cross-service CONNECT permitted")
            if failures:
                raise CheckError("Readiness failed: " + ", ".join(failures) + ".")
            if service != "KEYCLOAK":
                migration_dir = HERE.parents[1] / "services" / (service.lower() + "-service") / "src/main/resources/db/migration"
                expected = {file.name.split("__", 1)[0][1:]: file.name for file in migration_dir.glob("V*__*.sql")}
                history = postgres(values, url.hostname, database, username, password,
                    "SELECT json_build_object('migrations',coalesce(json_agg(json_build_object('version',version,'script',script,'success',success)),'[]')) FROM public.flyway_schema_history WHERE version IS NOT NULL;",
                    query["sslmode"][0], query.get("sslrootcert", [None])[0])
                rows = history["migrations"]
                applied = {row["version"]: row["script"] for row in rows if row["success"]}
                if not expected or applied != expected or any(not row["success"] for row in rows):
                    raise CheckError("Flyway history does not match this checkout's successful versioned migration set; owner review required (no migrations executed).")
            print(f"{database}: PASS — TLS, dedicated role, own permissions, cross-service denial; schema history present.")
        except CheckError as error:
            print(f"{database}: BLOCKED — {error}")
            ok = False
    return ok


def compose_command(path):
    return ["docker", "compose", "--env-file", str(path), "-f", str(HERE / "compose.yaml"),
            "-f", str(HERE / "compose.supabase.yaml")]


def config(values, path):
    for service in SERVICES:
        target(values, service)
    if not shutil.which("docker"):
        raise CheckError("Install Docker Compose 2.24.4 or later.")
    env = dict(os.environ)
    env.update(values)
    env.pop("COMPOSE_PROFILES", None)
    result = subprocess.run(compose_command(path) + ["config", "--format", "json"],
                            env=env, capture_output=True, text=True)
    if result.returncode:
        raise CheckError("Docker Compose validation failed; raw output withheld because it may contain secrets.")
    try:
        services = json.loads(result.stdout)["services"]
        active = {name for name, spec in services.items() if not spec.get("profiles")}
        if LOCAL_DATABASES & active:
            raise CheckError("External mode would activate a local PostgreSQL container.")
        for name in active:
            if LOCAL_DATABASES & services[name].get("depends_on", {}).keys():
                raise CheckError("External mode retains a dependency on local PostgreSQL.")
    except (ValueError, KeyError):
        raise CheckError("Unexpected Docker Compose configuration response.") from None
    print("External Compose configuration valid; no local PostgreSQL in active services/dependencies. Secrets withheld.")
    return env


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("inspect", "check", "config", "start"))
    parser.add_argument("--env-file", type=Path, default=HERE / ".env")
    args = parser.parse_args()
    try:
        path = args.env_file.resolve()
        values = read_env(path)
        if args.action == "inspect":
            return 0 if inspect(values) else 1
        if args.action == "check":
            return 0 if check(values) else 1
        env = config(values, path)
        if args.action == "config":
            return 0
        if not check(values):
            print("Nothing started. Ask the database owner to resolve readiness blockers.")
            return 1
        command = compose_command(path) + ["up", "-d", "--wait", "--wait-timeout", "180"]
        if values.get("SWIFTDROP_IMAGE_TAG", "local") == "local":
            command.append("--build")
        # No local DB or SQL initializer is part of the active dependency graph.
        return subprocess.run(command, env=env).returncode
    except (CheckError, OSError) as error:
        message = str(error) if isinstance(error, CheckError) else "Required environment file or executable is unavailable."
        print("BLOCKED — " + message, file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
