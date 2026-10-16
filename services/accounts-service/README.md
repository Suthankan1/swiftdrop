# Accounts & Partners — business profile foundation

Refs #5 · Java 21 LTS · Spring Boot 4.1.1 · Maven Wrapper 3.10.0 · MVC · port 8081.

Original Accounts business steward: **Sinthuha**. Temporary implementation author:
**Suthankan1**, authorized on 2026-10-16 because of availability. Customer ownership
is unchanged. Thuvarahan retains merchant business extensions (#8), HarithKeshan
staff business reads (#14), and Suthankan1 JWT/security integration (#37).
No shared frontend, Gateway, identity deployment or ownership files are changed.

## Scope and structure

- `src/main/java/com/devarom/accountsservice/identity/`: internal trusted identity
  value and current-identity provider interface; neither verifies JWTs.
- `profile/`: read-only JPA entity, repository, transactional profile use case,
  response DTO, controller and sanitized domain/authentication problem responses.
- `security/ProfileSecurityConfiguration.java`: temporary fail-closed HTTP policy.
- `src/main/resources/db/migration/V1__create_account_profiles.sql`: Accounts-owned
  PostgreSQL table, uniqueness/check constraints and identity immutability trigger.
- `src/test/java/com/devarom/accountsservice/`: unit/MVC contract/security tests
  and real Testcontainers PostgreSQL integration tests (`*IT`, Maven Failsafe).

Only Accounts accesses `accounts_db`. Keycloak owns identity/passwords and its
separate identity storage (`keycloak_db` when PostgreSQL-backed). No passwords,
tokens, authoritative roles, merchant approvals or foreign service tables exist in
the Accounts schema. No shared entity JAR, cross-service SQL or cross-service FK.
The existing identity Compose remains untouched; it does not provide an Accounts DB.

## Database and local startup

Requires Java 21 and PostgreSQL 16+; Docker is needed for Testcontainers.
The wrapper resolves dependencies from Maven Central. Boot 4.1.1 manages Flyway
12.4.0 and Testcontainers 2.0.5; Accounts uses the Boot 4 MVC/Flyway starters and
`flyway-database-postgresql`, without Spring Cloud or WebFlux.
See [Spring database initialization](https://docs.spring.io/spring-boot/how-to/data-initialization.html)
and [Testcontainers support](https://docs.spring.io/spring-boot/reference/testing/testcontainers.html).

If there is an approved PostgreSQL server already running, ask its administrator
to create a dedicated Accounts login role and `accounts_db` owned by that role.
Use interactive `createuser --pwprompt accounts_app` and
`createdb --owner=accounts_app accounts_db` with the administrator's connection
settings. Do not point Accounts at Keycloak's database or reuse its role.

Alternatively, start this dedicated local development PostgreSQL container
(zsh; enter your own non-empty local password at the hidden prompt):

```zsh
read -s 'POSTGRES_PASSWORD?Accounts local database password: '; echo
export POSTGRES_PASSWORD
# Uses its own named volume; never remove/reinitialize another developer's volume.
docker run -d --name devarom-accounts-postgres \
  -e POSTGRES_USER=accounts_app -e POSTGRES_DB=accounts_db -e POSTGRES_PASSWORD \
  -p 127.0.0.1:5432:5432 -v devarom-accounts-postgres-data:/var/lib/postgresql/data \
  postgres:16-alpine
export ACCOUNTS_DB_URL=jdbc:postgresql://localhost:5432/accounts_db
export ACCOUNTS_DB_USERNAME=accounts_app
export ACCOUNTS_DB_PASSWORD="$POSTGRES_PASSWORD"
unset POSTGRES_PASSWORD
```

If port 5432 is occupied, select a free host port and update `ACCOUNTS_DB_URL`.
For a pre-existing server, export the same three `ACCOUNTS_DB_*` variables with
its dedicated role and URL; enter the password through a hidden prompt. No
credential defaults or tracked `.env` file are supplied. Spring does not
implicitly load `.env` files. A missing DB configuration or unavailable DB prevents
startup; it does not fall back to an in-memory database.

```zsh
export JAVA_HOME=$(/usr/libexec/java_home -v 21) # macOS; select JDK 21 on other OSes
cd services/accounts-service
./mvnw clean verify
./mvnw spring-boot:run
# Or after verify: java -jar target/accounts-service-0.0.1-SNAPSHOT.jar
```

Startup runs Flyway V1 then Hibernate `ddl-auto=validate`; no `update`, clean,
drop or recreate operation is configured. Do not edit an applied migration;
add a reviewed V2 migration for later changes. No Kafka/Keycloak/gRPC/GraphQL
process is needed for these profile tests or this fail-closed startup.

```sh
curl -i http://localhost:8081/actuator/health # 200, status UP when DB is healthy
curl -i http://localhost:8081/api/v1/users/me # 401, application/problem+json
```

Only GET `/actuator/health` is public, without component details. All other routes
are denied. Basic auth, form login, generated password users and login shortcuts
are disabled. Even an authenticated test principal is denied (403). Arbitrary
bearer strings, query parameters and identity headers cannot enable profile access.

## Profile and frozen REST contract

`GET /api/v1/users/me` is prepared at port 8081. Gateway forwards the same path
from port 8080 with no added prefix. The response matches
[`CurrentUserProfile`](../../frontend/src/features/customer/api/contracts.ts).
The actual production route remains **401/403 until #37**; this synthetic response
illustrates the controller contract, not a live authenticated result:

```json
{
  "id": "99040f35-9241-4f6b-a7a8-8049219a1b78",
  "keycloakSub": "synthetic-subject",
  "email": "customer@example.test",
  "fullName": "Synthetic Customer",
  "phoneNumber": "",
  "roles": ["CUSTOMER"],
  "merchantProfile": null,
  "createdAt": "2026-10-16T00:00:00Z"
}
```

Accounts generates UUID `id` in PostgreSQL; no caller-supplied profile ID is
accepted. `keycloakSub` is opaque, non-blank, unique, immutable and preserved
exactly (maximum 255 characters). The database also rejects ID/subject updates.
`createdAt` is a database-generated UTC instant. Email (maximum 320) and name
(maximum 255) seed the first profile only from verified claims. Missing optional
email/name and uncollected phone use **empty strings**, matching the frozen
non-null frontend string fields without fabricating personal information.
Phone is initially empty (column supports 50 characters). No profile update API
is included. Repeat login preserves the original business attributes and ID.

Roles are copied from the current trusted verified context on each response;
they are never persisted, inferred, granted or accepted from an HTTP body.
An empty verified role list stays empty. #37 must validate/map allowed identity
roles before constructing this context. `merchantProfile` is always null;
the nested DTO merely preserves the existing future contract shape. #8 owns
actual merchant records/approval; #14 owns staff read extensions. Coordinate
shared profile/security file changes with the business steward and reviewers.

The service looks up only the trusted subject. On a miss, PostgreSQL
`INSERT ... ON CONFLICT ON CONSTRAINT uq_account_profiles_keycloak_sub DO NOTHING`
atomically resolves duplicate first logins. A subsequent SELECT under explicitly
configured READ COMMITTED sees the committed winner. Only the named expected
subject conflict is ignored; other DB errors propagate. There is no global retry
loop or client-selectable owner/ID. Profiles are immutable through this repository;
there are no inherited public save/delete methods or entity setters.

Success responses carry `Cache-Control: no-store`. Authentication and domain
errors use RFC ProblemDetail-compatible JSON; shape-validation errors are 400,
missing trusted identity is 401, policy denial is 403. Validation details and
SQL/stack traces are not returned. Unexpected database/programming errors remain
failures rather than fabricated profiles.

## Exact integration seam for Issue #37

1. Replace the deny policy in `security/ProfileSecurityConfiguration.java` with
   independent OAuth2 Resource Server validation (signature/JWKS, issuer, audience,
   lifetime), role mapping and access policy. Add the appropriate Boot 4 Resource
   Server dependency to `pom.xml`. Preserve public health only, no Basic/form auth.
2. Implement `identity/CurrentIdentityProvider.java` as a security-owned bean.
   Construct `TrustedIdentity` only from verified JWT claims/security context;
   never from `X-User-Id`, `X-Keycloak-Sub`, request parameters or unverified tokens.
   The conditional default provider only throws; it supplies no identity.
3. Keep `profile/CurrentUserProfileController.java` calling the provider and
   `CurrentUserProfileService.getOrCreate(TrustedIdentity)`. No controller parameter
   accepts an ID/subject and the application service never authenticates a token.
4. Extend `ProfileHttpSecurityTest` and `AccountProfilePostgresIT` with actual signed
   JWT positive/negative tests, direct-service bypass tests and role/owner denials.
   Keep synthetic controller-only contract tests distinguished from runtime proof.

#37 is not implemented here. Live Keycloak authenticated `/me`, role enforcement
and React → Gateway → Accounts E2E remain deferred. This PR does not complete the
November 1 login acceptance criteria or approve merchant/staff extensions.

## Verification evidence — 2026-10-16

Verified with OpenJDK **21.0.12.1**, Maven **3.10.0**, Boot **4.1.1**,
Docker Desktop **4.95.0** / Engine **29.8.2**, and PostgreSQL **16.14**
(`postgres:16-alpine` resolved at test time).

- `JAVA_HOME=$(/usr/libexec/java_home -v 21) services/accounts-service/mvnw -f services/accounts-service/pom.xml clean verify -q`:
  **passed**, 22 unit/MVC tests + 12 PostgreSQL integration tests; zero failures,
  errors or skips. Includes clean Flyway V1, JPA validation/readback, DB uniqueness,
  immutable ID/subject updates, targeted conflict no-op, separate owners, absent
  attributes, eight concurrent first-login requests, DTO JSON and HTTP denials.
- Packaged `java -jar target/accounts-service-0.0.1-SNAPSHOT.jar` against a second
  disposable PostgreSQL database: **started on default port 8081**, applied V1,
  health HTTP **200 / UP**, anonymous and spoofed bearer/header profile requests
  **401 / ProblemDetail**. Smoke process/container stopped afterward; no developer
  database, volume or Keycloak process was modified.
- `git diff --check`: passed. Staged source/config reviewed for secrets; only
  synthetic fixtures and runtime environment-variable references are committed.
- Frontend fields/path and Gateway target/path inspected: compatible; no source
  changes to those modules. Live authenticated HTTP success/E2E **not verified**.

Docker tests are required by `verify` and are not silently skipped if Docker is unavailable.
For a machine without Docker, `./mvnw test` runs only unit/MVC tests; report the
PostgreSQL integration phase as blocked and run `./mvnw verify` on a Docker host.
