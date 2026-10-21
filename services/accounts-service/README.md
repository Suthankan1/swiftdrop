# Accounts & Partners — business profile foundation

Refs #5 · Java 21 LTS · Spring Boot 4.1.1 · Maven Wrapper 3.10.0 · MVC · port 8081.

Original Accounts business steward: **Sinthuha**. Temporary implementation author:
**Suthankan1**, authorized on 2026-10-16 because of availability. Customer ownership
is unchanged. Thuvarahan retains merchant business extensions (#8), HarithKeshan
staff business reads (#14), and Suthankan1 JWT/security integration (#37).
No shared frontend, Gateway, identity deployment or ownership files are changed.

## Scope and structure

- `src/main/java/com/swiftdrop/accountsservice/identity/`: internal trusted identity
  value and current-identity provider interface; neither verifies JWTs.
- `profile/`: read-only JPA entity, repository, transactional profile use case,
  response DTO, controller and sanitized domain/authentication problem responses.
- `security/ProfileSecurityConfiguration.java`: temporary fail-closed HTTP policy.
- `src/main/resources/db/migration/V1__create_account_profiles.sql`: Accounts-owned
  PostgreSQL table, uniqueness/check constraints and identity immutability trigger.
- `src/test/java/com/swiftdrop/accountsservice/`: unit/MVC contract/security tests
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
docker run -d --name swiftdrop-accounts-postgres \
  -e POSTGRES_USER=accounts_app -e POSTGRES_DB=accounts_db -e POSTGRES_PASSWORD \
  -p 127.0.0.1:5432:5432 -v swiftdrop-accounts-postgres-data:/var/lib/postgresql/data \
  postgres:16-alpine
# Note: For existing environments with devarom-accounts-postgres, existing container and volume are retained.
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

## Accounts JWT Resource Server Security — Issue #37

Refs #37 · Implementation Author: **Suthankan1** · Reviewers: **Sinthuha**, **Thuvarahan**, **Vibhushan**.

Accounts Service now independently validates Keycloak Bearer JWTs as an OAuth2 Resource Server:
- **Cryptographic Signature Verification:** `NimbusJwtDecoder` independently verifies JWT signatures against trusted Keycloak JWKS (`/protocol/openid-connect/certs`).
- **Composed Token Validation:** Validates exact issuer (`KEYCLOAK_ISSUER_URI`), required API audience (`JWT_AUDIENCE=swiftdrop-api`), timestamps (expiration, not-before with default clock skew), and non-blank subject (`sub <= 255` characters).
- **Application Role Mapping:** `KeycloakRoleExtractor` extracts authoritative roles from `roles` and `realm_access.roles` claims, strictly filtering to the five approved SwiftDrop roles (`CUSTOMER`, `MERCHANT`, `COURIER`, `DISPATCHER`, `ADMIN`). Unrecognized roles, client-supplied headers, and Keycloak default/internal roles (`default-roles-swiftdrop`, `default-roles-devarom`, `offline_access`, `uma_authorization`) are safely ignored.
- **Trusted Identity Provider:** `SecurityContextCurrentIdentityProvider` extracts validated identity attributes (`subject`, `email`, `fullName`, and approved `roles`) exclusively from the authenticated Spring Security `Jwt` principal. HTTP headers (`X-User-Id`, `X-Keycloak-Sub`, `X-Roles`, `X-User-Email`) and query parameters are never trusted.
- **HTTP Security Policy:**
  - Stateless session management (`SessionCreationPolicy.STATELESS`), CSRF disabled for stateless API.
  - Public access restricted strictly to `GET /actuator/health` and `/actuator/health/**`.
  - `GET /api/v1/users/me` requires bearer authentication with an approved application role (`hasAnyRole`).
  - Default-deny policy (`anyRequest().denyAll()`) rejects unapproved routes.
  - Basic authentication, form login, server-side sessions, and client-side password grants remain disabled.
  - Sanitized RFC 7807 `ProblemDetail` responses for 401 Unauthorized and 403 Forbidden without leaking internals.

## Verification evidence — 2026-10-16

Verified with OpenJDK **21.0.12.1**, Maven **3.10.0**, Boot **4.1.1**,
Docker Desktop **4.95.0** / Engine **29.8.2**, and PostgreSQL **16.14**
(`postgres:16-alpine` resolved at test time).

- `JAVA_HOME=$(/usr/libexec/java_home -v 21) services/accounts-service/mvnw -f services/accounts-service/pom.xml clean verify`:
  **passed: 37 unit/MVC tests + 16 real Testcontainers PostgreSQL integration tests (53 tests total)**; zero failures, errors, or skips.
  - **Unit/MVC tests (`ProfileHttpSecurityTest` — 20 tests):**
    1. Missing token -> 401 Unauthorized ProblemDetail
    2. Malformed token -> 401 Unauthorized ProblemDetail
    3. Unsigned token -> 401 Unauthorized ProblemDetail
    4. Untrusted signature -> 401 Unauthorized ProblemDetail
    5. Wrong issuer -> 401 Unauthorized ProblemDetail
    6. Wrong audience -> 401 Unauthorized ProblemDetail
    7. Expired token -> 401 Unauthorized ProblemDetail
    8. Not-yet-valid token -> 401 Unauthorized ProblemDetail
    9. Missing subject -> 401 Unauthorized ProblemDetail
    10. Malformed application roles -> safe 403 rejection
    11. Missing required application role -> 403 Forbidden
    12. Valid CUSTOMER JWT -> 200 OK profile access with `Cache-Control: no-store`
    13. Valid MERCHANT JWT -> 200 OK with correct role mapping
    14. Unauthorized/unrecognized role does not gain access -> 403 Forbidden
    15. Spoofed headers (`X-User-Id`, `X-Keycloak-Sub`, `X-Roles`, `X-User-Email`) and query parameters ignored
    16. Direct bearer authentication works independently without API Gateway
    17. Public health endpoint remains accessible without credentials
    18. Basic and form login remain completely unavailable (no Basic WWW-Authenticate challenge)
    19. Unexposed actuator endpoints (`/actuator`, `/actuator/env`) denied with 401
    20. Correct RFC 7807 ProblemDetail and `Cache-Control: no-store` behavior verified
  - **PostgreSQL integration tests (`AccountProfilePostgresIT` — 16 tests):**
    - Customer A authenticates and receives persisted profile A
    - Repeated Customer A request returns the exact same profile ID
    - Customer B authenticates and receives a distinct profile ID
    - Customer A cannot select or retrieve Customer B's profile via query params or spoofed headers
    - Concurrent authenticated first requests create exactly one persisted profile row
    - Rejected tokens (expired, untrusted, wrong audience, wrong role, malformed) never create a profile row
    - Merchant authentication maps `MERCHANT` role with `merchantProfile = null`
    - Flyway V1 clean migration and Hibernate schema validation pass cleanly
- `git diff --check`: passed; zero secrets, private keys, or passwords committed.
- Static and live Keycloak invariants (`platform/identity/validate-identity.sh`): all 7 static checks and 5 live container smoke checks passed against live Keycloak 26.8.1 on port 8090.
- Browser E2E flow (React -> Keycloak -> Gateway -> Accounts -> PostgreSQL) and Google broker acceptance (L03) remain unverified until interactive user browser authentication is exercised.
