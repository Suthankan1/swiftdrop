# Supabase PostgreSQL for shared team development

This opt-in configuration retains five independent databases, existing service
boundaries and Keycloak identity. It does not use Supabase Auth or change any
Flyway migration. Local PostgreSQL remains the default.

| Supabase project | Database | Runtime role | Consumer |
|---|---|---|---|
| swiftdrop-accounts | `accounts_db` | `accounts_app` | accounts-service |
| swiftdrop-business | `pricing_db` | `pricing_app` | pricing-service |
| swiftdrop-business | `delivery_db` | `delivery_app` | delivery-service |
| swiftdrop-business | `communications_db` | `communications_app` | communications-service |
| swiftdrop-identity | `keycloak_db` | `keycloak` | Keycloak |

## Obtain and store access

Ask the project/database owner for access to the appropriate project, its exact
Session Pooler hostname, and a verified dedicated service login. Custom pooler
usernames use `ROLE.PROJECT_REF`; obtain the complete value rather than assuming
the role exists or reusing the administrator password. Administrative
`postgres.PROJECT_REF` logins are for reviewed inspection/provisioning only.

Keep settings in ignored `platform/compose/.env`, with permissions `600`.
Preserve existing unrelated secrets. Each service uses its existing
`ACCOUNTS_DB_*`, `PRICING_DB_*`, `DELIVERY_DB_*`, `COMMUNICATIONS_DB_*`, or
`KEYCLOAK_DB_*` URL, username and password variables. URLs contain no password:

```dotenv
ACCOUNTS_DB_URL=jdbc:postgresql://SESSION_POOLER_HOST:5432/accounts_db?sslmode=require
ACCOUNTS_DB_USERNAME=accounts_app.PROJECT_REF
ACCOUNTS_DB_PASSWORD='SERVICE_PASSWORD'
```

Use the same pattern for each table row. Copy exact hostnames from the owner;
never change the database path to `postgres` to work around a missing database.
Quoted dotenv passwords preserve special characters. Do not source this file
into a shell, print it, or run unfiltered `docker compose config` against it.

Set `SUPABASE_{ACCOUNTS,BUSINESS,IDENTITY}_{HOST,PROJECT_REF}` to the exact
owner-supplied routing values. Optional inspection credentials use
`SUPABASE_{ACCOUNTS,BUSINESS,IDENTITY}_{ADMIN_USERNAME,ADMIN_PASSWORD}`.
These values are read by the helper and are not passed to application
containers. Initially supplied administrator credentials do not establish that
the dedicated runtime roles exist or that they use the same password.

[Supabase Session Pooler](https://supabase.com/docs/guides/database/connecting-to-postgres)
uses port `5432`; session mode supports long-lived JDBC sessions. The configured
`sslmode=require` requires encryption.
[Certificate and hostname verification](https://supabase.com/docs/guides/platform/ssl-enforcement)
requires `verify-full` and the appropriate trusted CA configuration; `require`
alone does not authenticate the server certificate.

## Validate before startup

From the repository root, with Python 3, `psql`, and Docker Compose available:

```bash
python3 platform/compose/supabase.py config
python3 platform/compose/supabase.py inspect
python3 platform/compose/supabase.py check
```

`config` validates merged configuration without printing credentials. `inspect`
uses the supplied administrative connections for read-only catalog discovery.
`check` verifies the dedicated runtime logins, correct databases, TLS,
permissions and isolation, and compares successful Flyway version/script records
with this checkout. It does not run Flyway or verify migration checksums.
Client TLS is checked using `psql` connection information; `pg_stat_ssl`
describes the separate pooler-to-database hop and is reported separately.
A reachable shared pooler does not prove that an
individual project is active: successful authentication and database checks are
also required. Confirm project status with its owner if those fail.

Missing databases or roles must be reported to the owner. The inert
[SQL review draft](supabase-provision.review.sql) contains separate project
sections; it is never executed by the helper. Provisioning and changes to
existing ownership require explicit owner review. An existing database whose
tables belong to another role needs an object-level review; creating a new role
or granting schema access does not transfer table ownership.

## Choose a database mode

Default local startup uses the saved, ignored `.env.local-postgres` when present,
otherwise `.env`. The saved file preserves the original local values while
`.env` holds the external settings:

```bash
./platform/compose/start.sh
```

Before first configuring external access, save the existing local file without
overwriting a previous snapshot:

```bash
test -e platform/compose/.env.local-postgres || cp -p platform/compose/.env platform/compose/.env.local-postgres
chmod 600 platform/compose/.env platform/compose/.env.local-postgres
```

The existing scripts also accept `SWIFTDROP_COMPOSE_ENV_FILE` for selecting a
different local environment file.

External startup is explicitly selected and first repeats the runtime checks:

```bash
python3 platform/compose/supabase.py start
# Equivalent entry point:
./platform/compose/start.sh --supabase
```

Stop external application containers with `./platform/compose/stop.sh --supabase`;
inspect their database readiness with `./platform/compose/status.sh --supabase`.

The external overlay excludes local PostgreSQL startup and its initialization
scripts, retains Kafka, MinIO, Mailpit and the existing application services,
and removes only database dependencies that no longer apply. Switch modes only
after stopping the existing runtime; preserve its volumes.

Shared schemas must already have the approved migrations applied by their
owners before external startup. Flyway is disabled in this mode; Hibernate
continues to validate the existing schema. Keycloak uses
[schema validation](https://www.keycloak.org/server/db) with empty-database
initialization disabled and no realm import. Its compatible database schema,
SwiftDrop realm, clients and required settings must already be provisioned.
These protections intentionally fail startup for an empty or incompatible
database. Starting the runtime still permits normal application data writes.

Each application pool is capped at two connections per developer machine, with
zero minimum idle connections. The business project can therefore receive up to
six application connections per machine, plus administrator/migration sessions;
Keycloak has its own small pool. Agree a team-wide concurrent-user budget with
the owner against the project's actual limits. Do not raise pool sizes to mask
connection exhaustion.

## Shared migrations and troubleshooting

One designated owner applies each reviewed migration set once, records its
version, and coordinates the matching application revision. Other developers
leave automatic migrations disabled. Never edit applied migrations or run
Flyway repair/clean, database resets, or Keycloak schema updates as a connection
fix. The local `verify.sh` and `smoke.sh` contain local-container checks and
writes; use the read-only helper for external database validation.

| Failure | Next check |
|---|---|
| DNS/TCP timeout | Exact pooler hostname, VPN/firewall, network access, project status |
| Tenant or user not found | Correct project suffix, full pooler username, role existence |
| Password authentication failed | Owner-issued password for that role; quoting in `.env` |
| Database does not exist | Correct named database and reviewed provisioning; retain all five databases |
| Permission or isolation check fails | Owner's database/schema/table grants and inherited role memberships |
| Too many clients/connections | Other team sessions, pooler limits and aggregate pool budget |
| Hibernate/Keycloak validation fails | Owner-approved schema version and matching image; do not enable auto migration |

No successful live connectivity or compatibility claim follows from static
configuration validation alone. Keep the actual `inspect`/`check` output as the
readiness evidence, without secrets.
