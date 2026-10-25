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
- Static and live Keycloak invariants (`platform/identity/validate-identity.sh`): all 7 static checks and 5 live container smoke checks passed against live Keycloak 26.8.1 on port 8090.
- Browser E2E flow (React -> Keycloak -> Gateway -> Accounts -> PostgreSQL) and Google broker acceptance (L03) remain unverified until interactive user browser authentication is exercised.

## Merchant Approval-State & Tenant Authorization Provider — Issue #8

Refs #8 · Author: **Thuvarahan** (Merchant team) · Reviewers: **Sinthuha**, **HarithKeshan**, **Eric**, **Vibhushan**.

Accounts Service now provides the minimal merchant approval-state representation, in-process provider interfaces, and tenant authorization boundary required for role landing and tenant isolation:
- **Database Boundary (`V2__create_merchants.sql`):** Accounts owns the `merchants` table and Flyway V2 migration. A merchant tenant binds an immutable `id` (UUID), `account_profile_id`, and `keycloak_sub` (subject claim) with unique registration numbers and check constraints on approval status. A PostgreSQL trigger (`merchant_identity_immutable`) strictly prevents tampering with tenant identity or subject binding on UPDATE.
- **Decoupled Approval Lifecycle (`MerchantStatus`):** States `PENDING`, `APPROVED`, `SUSPENDED`, `REJECTED`. Keycloak authentication establishes identity, but does **not** imply business approval. Registration defaults to non-operational pending status; self-approval by client parameters or HTTP headers is impossible.
- **Provider Interfaces:**
  - `MerchantApprovalProvider`: Resolves `MerchantStatus` by tenant ID or Keycloak subject (`getApprovalStatus`, `isApproved`).
  - `MerchantTenantAuthorizationProvider`: Enforces tenant ownership checks (`authorizeTenantAccess`, `requireTenantAccess`, `requireApprovedTenantAccess`, `resolveMerchantForIdentity`).
- **Fail-Closed Security & Role Policy:**
  - Only authenticated actors with `MERCHANT` or `ADMIN` roles can access merchant endpoints (`/api/v1/merchants/**`).
  - Strict tenant ownership: Merchant A cannot read or modify Merchant B's resources (throws `TenantAccessDeniedException` -> RFC 7807 403 Forbidden).
  - Admin oversight: Verified administrators may inspect tenant records for review and onboarding approval.
  - Operations requiring approval fail closed with `MerchantNotApprovedException` (403 Forbidden) if status is `PENDING`, `SUSPENDED`, or `REJECTED`.
- **Integrated Profile Resolution:** `GET /api/v1/users/me` automatically populates the frozen `merchantProfile` contract (`merchantId`, `businessName`, `registrationNumber`, `status`) when an authenticated `MERCHANT` has a bound merchant record, and returns `null` for `CUSTOMER` actors.
- **REST Seams:** `GET /api/v1/merchants/me`, `GET /api/v1/merchants/{merchantId}`, `GET /api/v1/merchants/{merchantId}/status` guarded by OAuth2 Resource Server and tenant authorization.

## Staff Profile Foundation & Controlled Provisioning — Issue #14

Refs #14 · Author: **HarithKeshan** (Dispatcher/Admin Backend Lead) · Reviewers: **Sinthuha**, **Suthankan1**, **Abhinaya**, **Pushmitha**.

Accounts Service now provides the minimal staff business profile foundation, read models, repository, and controlled provisioning contract required for `COURIER`, `DISPATCHER`, and `ADMIN` operational workspaces:

### 1. Staff Profile Data Ownership & Role Distinctions
- **Data Owner:** Accounts & Partners Service owns the `staff_profiles` domain model and persistence. Keycloak remains the authoritative source for authenticated realm roles.
- **Three Supported Staff Roles:**
  - `COURIER`: Field logistics personnel assigned to delivery zones for pickup and drop-off operations.
  - `DISPATCHER`: Operational coordinators monitoring unassigned parcel queues and assigning workloads.
  - `ADMIN`: Platform administrators managing merchant approvals, system configuration, and staff onboarding.
- **Role Decoupling:** Authentication verifies who the caller is via Keycloak; the business profile records domain-specific operational attributes (`staffId`, `department`, `assignedZone`, `businessStatus`).

### 2. Database Schema & Migration (`V3__create_staff_profiles.sql`)
- **Table:** `staff_profiles`
  - `id`: UUID primary key (immutable).
  - `account_profile_id`: Optional UUID foreign key referencing `account_profiles(id)`.
  - `keycloak_sub`: Immutable Keycloak subject reference (VARCHAR(255), UNIQUE, non-blank check constraint).
  - `staff_id`: Unique employee/staff identifier (VARCHAR(50), UNIQUE, non-blank check constraint, e.g. `STF-2026-0042`).
  - `staff_role`: VARCHAR(30) with check constraint `CHECK (staff_role IN ('COURIER', 'DISPATCHER', 'ADMIN'))`.
  - `department`: VARCHAR(100), non-blank check constraint (e.g. `FIELD_LOGISTICS`, `DISPATCH_OPERATIONS`, `PLATFORM_ADMINISTRATION`).
  - `assigned_zone`: Optional VARCHAR(100) (e.g. `ZONE-WEST-COLOMBO` for couriers).
  - `status`: VARCHAR(30) with check constraint `CHECK (status IN ('ACTIVE', 'SUSPENDED', 'INACTIVE'))`.
  - `created_at`: TIMESTAMP WITH TIME ZONE NOT NULL.
  - `updated_at`: TIMESTAMP WITH TIME ZONE NOT NULL.
- **Identity Immutability Trigger (`staff_identity_immutable`):** Enforces at the PostgreSQL engine level that `id` and `keycloak_sub` cannot be altered on `UPDATE`. Status and operational assignments may be updated by authorized processes.

### 3. API Read Contract (`GET /api/v1/staff/me` and `GET /api/v1/staff/profile`)
- **Endpoints:**
  - Primary: `GET /api/v1/staff/me`
  - Alias: `GET /api/v1/staff/profile`
- **Method:** `GET`
- **Security:** Requires Keycloak Bearer JWT with `COURIER`, `DISPATCHER`, or `ADMIN` role.
- **Headers:**
  - `Authorization: Bearer <keycloak-jwt>`
  - `Accept: application/json`
- **Response Headers:** `Cache-Control: no-store`
- **Response Shape (200 OK):**
```json
{
  "profileId": "a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d",
  "id": "a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d",
  "staffId": "STF-2026-0042",
  "keycloakSub": "c3d4e5f6-a7b8-9c0d-1e2f-3a4b5c6d7e8f",
  "displayName": "Kamal Perera",
  "email": "courier.kamal@swiftdrop.test",
  "phoneNumber": "+94771122334",
  "role": "COURIER",
  "department": "FIELD_LOGISTICS",
  "assignedZone": "ZONE-WEST-COLOMBO",
  "businessStatus": "ACTIVE",
  "status": "ACTIVE",
  "createdAt": "2026-10-18T08:30:00Z",
  "updatedAt": "2026-10-18T08:30:00Z"
}
```
- **Error Responses (RFC 7807 ProblemDetail):**
  - `401 Unauthorized`: Missing, expired, or invalid Bearer token.
  - `403 Forbidden`: Caller lacks a staff role (`CUSTOMER` or `MERCHANT`), role mismatch between JWT and profile, or staff profile is `SUSPENDED`/`INACTIVE`.
  - `404 Not Found`: Authenticated staff identity does not have a provisioned business profile in Accounts.

### 4. Controlled Staff Provisioning Business Contract
- **No Public Self-Registration:** Public registration remains strictly customer-only. Staff identities cannot self-assign `COURIER`, `DISPATCHER`, or `ADMIN` roles.
- **Authorized Provisioning Workflow:**
  1. **Administrative Identity Creation:** An authorized administrator creates the user identity in Keycloak or validates an institutional employee directory.
  2. **Role Assignment:** Keycloak administrator assigns the appropriate realm role (`COURIER`, `DISPATCHER`, or `ADMIN`).
  3. **Accounts Business Profile Provisioning:** Accounts receives the authorized provisioning command (`ProvisionStaffCommand`) containing `keycloakSub`, `staffId`, `role`, `department`, and `assignedZone`.
  4. **Validation & Uniqueness:** Accounts validates that `keycloakSub` and `staffId` are globally unique, and initializes the business profile in `ACTIVE` status.
  5. **Self-Read Resolution:** When the staff member signs in, `GET /api/v1/staff/me` resolves the provisioned business profile.
  6. **Unprovisioned/Mismatched Access Denial:** If a staff member logs in without an Accounts business profile, they receive 404 Not Found. If their Keycloak token role disagrees with their registered profile role, access is denied (403 Forbidden).
- **Deferred Operations:** A public or unauthenticated staff creation REST endpoint is strictly avoided. Future administrative bulk-import or UI-driven provisioning will connect directly through the validated internal service method with audit logging.

### 5. Frontend Consumer Integration Handoff
- **Courier Landing (`frontend/src/features/courier/` — Abhinaya):**
  - Endpoint: `GET /api/v1/staff/me`
  - Guard: `ProtectedRoute role="COURIER"`
  - Display fields: `displayName`, `staffId`, `department`, `assignedZone`, `businessStatus`.
  - Missing-profile handling: If `GET /api/v1/staff/me` returns 404, present a secure "Staff Profile Not Yet Provisioned — Contact Operations" notice.
- **Dispatcher & Admin Landings (`frontend/src/features/dispatcher/` and `features/admin/` — Pushmitha):**
  - Endpoint: `GET /api/v1/staff/me`
  - Guard: `ProtectedRoute role="DISPATCHER"` or `ProtectedRoute role="ADMIN"`
  - Display fields: `displayName`, `staffId`, `department`, `businessStatus`.
  - Isolation: Courier-specific fields like `assignedZone` may be null for Dispatcher/Admin.

## Merchant API Credential Lifecycle — Issue #22

Refs #22 · Author: **Thuvarahan** (Merchant Backend Lead) · Reviewers: **Sinthuha** (Accounts DB & provider boundaries), **Vibhushan** (Delivery contract & independent security QA), **Eric** (Merchant UI consumer — #120), **Suthankan1** (Platform coordination).

Accounts Service provides the authoritative Merchant API Key lifecycle, persistence, one-way SHA-256 hashing, tenant ownership verification, approval state enforcement, and metadata management:

### 1. Architectural Decisions & Security Invariants
- **Machine Credential Approach:** Scoped API keys with prefix `swd_live_` and 32 characters of high-entropy Base62 random bytes (`DEC-MER-01`, [ADR-0010](../../docs/architecture/adr/ADR-0010-merchant-credential-lifecycle.md)). Example: `swd_live_7xK9mP2vL8wQ4zR1tY6uI0oA5sD3fG8h`.
- **Zero Plaintext Storage:** Plaintext API key secrets are returned **strictly once** upon generation (`ApiKeyCreatedResponse`) and never stored. Accounts persists only the lowercase hex SHA-256 digest (`DEC-MER-02`).
- **Constant-Time Verification:** Key validation uses constant-time digest comparison (`MessageDigest.isEqual`) to eliminate timing side-channel attacks.
- **Strict Tenant Confinement:** A merchant can manage only their own keys. Resolves merchant tenant from authenticated Keycloak `sub`. Cross-tenant attempts fail closed with RFC 7807 403 Forbidden (`TENANT_ACCESS_DENIED`).
- **Approval State Gate:** Machine credentials can only be issued to and validated for merchants in `APPROVED` business status. `PENDING`, `SUSPENDED`, or `REJECTED` merchants fail closed (`MERCHANT_NOT_APPROVED` -> 403 Forbidden) (`DEC-MER-03`).
- **Active Key Limit & Rotation:** Up to 3 active keys allowed concurrently to support zero-downtime key rotation before revoking older keys (`DEC-MER-04`). Issuing a 4th active key returns `409 Conflict` (`API_KEY_LIMIT_EXCEEDED`).
- **Durable Revocation:** Revoking a key persists `revoked_at = NOW()`. Revocation is idempotent (`204 No Content`). Subsequent verification calls reject revoked keys (`401 Unauthorized`).
- **Confidentiality Headers:** All responses emit `Cache-Control: no-store`. ProblemDetail errors sanitize sensitive values.

### 2. Database Schema & Migration (`V4__create_merchant_api_keys.sql`)
- **Table:** `merchant_api_keys`
  - `id`: UUID PRIMARY KEY (immutable).
  - `merchant_id`: UUID NOT NULL, REFERENCES `merchants(id)` ON DELETE RESTRICT.
  - `name`: VARCHAR(100) NOT NULL.
  - `key_prefix`: VARCHAR(16) NOT NULL (e.g. `swd_live_7xK9`).
  - `key_digest`: VARCHAR(64) NOT NULL UNIQUE (SHA-256 lowercase hex).
  - `scopes`: VARCHAR(255) NOT NULL (comma-separated, e.g. `parcels:create,parcels:read`).
  - `expires_at`: TIMESTAMP WITH TIME ZONE NOT NULL.
  - `revoked_at`: TIMESTAMP WITH TIME ZONE NULL.
  - `created_at`: TIMESTAMP WITH TIME ZONE NOT NULL.
- **Indexes:**
  - `idx_merchant_api_keys_merchant_id` on `(merchant_id)`.
  - `idx_merchant_api_keys_key_prefix` on `(key_prefix)`.
- **Identity & Digest Immutability Trigger:** `merchant_api_key_immutable` enforces at the PostgreSQL engine level that `id`, `merchant_id`, `key_digest`, and `created_at` cannot be updated.

### 3. REST API Contract
- `POST /api/v1/merchants/{id}/api-keys`: Creates key, returns plaintext secret once (`201 Created`).
- `GET /api/v1/merchants/{id}/api-keys`: Lists active/expired/revoked key metadata without secrets or digests (`200 OK`).
- `DELETE /api/v1/merchants/{id}/api-keys/{keyId}`: Idempotently revokes key (`204 No Content`).
- `POST /api/v1/merchants/{id}/api-keys/{keyId}/revoke`: Alias for revocation (`204 No Content`).

### 4. Internal In-Process Validation Interface
- `MerchantApiKeyValidator.validateKey(String rawApiKey, String requiredScope)`:
  - Validates key format (`swd_live_...`).
  - Computes SHA-256 digest and performs constant-time comparison against persisted record.
  - Verifies merchant business status is `APPROVED`.
  - Verifies key is neither expired nor revoked.
  - Verifies granted scopes contain the required operation scope.
  - Serves as the internal foundation for gRPC `AccountsInternalService.ValidateMerchantKeyDigest` (`IF-GRPC-02`).

## Pending Merchant Enrollment Command — Issue #97

Refs #97 · Author: **Thuvarahan** (Merchant Backend Lead) · Reviewers: **Sinthuha** (Accounts DB & business service), **Harithkeshan** (Merchant/Admin boundary), **Eric** (Merchant UI consumer — #120), **Suthankan1** (Platform identity coordination).

Accounts & Partners Service provides the self-service business merchant enrollment command, trusted identity binding, server-controlled approval state lifecycle, and conflict/concurrency safety:

### 1. Architectural Decisions & Security Invariants
- **Reachable CUSTOMER Enrollment:** Authenticated users holding only the `CUSTOMER` role can enroll as a merchant via `POST /api/v1/merchants` without requiring a pre-existing `MERCHANT` role or duplicate Keycloak signup flow (`N10-WF07`).
- **Verified Identity Binding:** Caller's Keycloak subject (`sub`) is extracted securely from the verified Spring Security `SecurityContext` via `CurrentIdentityProvider.currentIdentity()`. Never accepts identity or tenant ownership from request body or unverified headers.
- **Trusted Profile Resolution:** Reuses the verified `AccountProfile` association via `AccountProfileRepository.findByKeycloakSub`. If absent on initial login/enrollment, executes JIT provisioning via conflict-safe `insertIfAbsent`.
- **Server-Enforced PENDING State:** Every newly enrolled merchant begins strictly in `MerchantStatus.PENDING`. No client-controlled approval or role claims are permitted.
- **Strict JSON Input Policy:** `MerchantRegistrationRequest` rejects client-supplied privileged/ownership fields (`merchantId`, `id`, `accountProfileId`, `keycloakSub`, `status`, `approved`, `roles`, `ADMIN`, `MERCHANT`, `createdAt`, `updatedAt`, `ownerId`) with HTTP 400 Bad Request ProblemDetail.
- **Duplicate Prevention & Concurrency Safety:**
  - Single merchant per user: Rejects enrollment if caller already owns a tenant (HTTP 409 Conflict).
  - Business registration uniqueness: Rejects duplicate `registrationNumber` across tenants (HTTP 409 Conflict).
  - Database engine uniqueness: Uniqueness constraints `uq_merchants_keycloak_sub` and `uq_merchants_registration_number` protect against concurrent race conditions, cleanly mapped to 409 Conflict while letting general persistence errors propagate without masking.
- **Same-Subject Pending Status Retrieval:**
  - Authenticated CUSTOMER callers can inspect their own pending tenant status via `GET /api/v1/merchants/{merchantId}/status` and retrieve their bound tenant via `GET /api/v1/merchants/me`.
  - Cross-tenant status probes and unauthorized operations fail closed with HTTP 403 Forbidden.
- **Privileged Operation Gates Preserved:** Privileged tenant actions (such as API credential generation or dispatch operations) require `APPROVED` status via `requireApprovedTenantAccess` and `MERCHANT` role, failing closed for PENDING merchants.
- **Confidentiality:** Returns `Cache-Control: no-store` on all responses containing identity or merchant state.

### 2. REST API Contract & Handoff for Frontend (#120 — Eric Praveen)
- **Enrollment Endpoint:** `POST /api/v1/merchants`
  - **Headers:** `Authorization: Bearer <access-token>`, `Content-Type: application/json`
  - **Request Body:**
    ```json
    {
      "businessName": "Lanka Retail Holdings Ltd",
      "registrationNumber": "PV-109923"
    }
    ```
  - **Validation:** `businessName` (3-255 characters, not blank), `registrationNumber` (3-100 characters, not blank).
  - **Response (`201 Created`):**
    ```json
    {
      "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
      "keycloakSub": "c56a4180-65aa-42ec-a945-5fd21dec0538",
      "businessName": "Lanka Retail Holdings Ltd",
      "registrationNumber": "PV-109923",
      "status": "PENDING",
      "createdAt": "2026-10-25T14:40:00Z",
      "updatedAt": "2026-10-25T14:40:00Z"
    }
    ```
  - **Cache Header:** `Cache-Control: no-store`
  - **Status Retrieval:** `GET /api/v1/merchants/me` or `GET /api/v1/merchants/{id}/status`
  - **Error Responses:**
    - `400 Bad Request`: Validation failure or prohibited fields present.
    - `401 Unauthorized`: Missing or invalid bearer token.
    - `403 Forbidden`: Non-customer/non-admin caller attempting enrollment, or cross-tenant access attempt.
    - `409 Conflict`: Caller already owns a merchant tenant or registration number already registered.

### 3. Integration Status & Pending Boundaries
- **Issue #8 & #88:** Merged baseline reused directly (merchant status enum, database schema V2, tenant authorization).
- **Issue #107 (Platform Role Reconciliation — Suthankan1):** In accordance with architecture boundaries, initial enrollment creates PENDING merchant state only and does not self-grant the Keycloak `MERCHANT` role. Role synchronization triggers upon administrative approval.
- **Issue #121 (Admin Approval/Suspend — Thuvarahan):** Admin operations transition status from `PENDING` to `APPROVED` or `REJECTED`. Not claimed or implemented in #97.
- **Issue #120 (Merchant Enrollment UI — Eric Praveen):** REST contract, validation rules, status path, and error semantics ready for React form integration.

---

## gRPC Internal Service & Accounts Eligibility / Scoped Profiles — Issue #109 (N10-B05)

Refs #109 · Author: **Gajanthan23** · Port: **9081** (default) · Status: **IMPLEMENTED & FULLY TESTED**

Accounts Service hosts the authoritative server-side gRPC implementation of `swiftdrop.accounts.v1.AccountsInternalService`, providing low-latency, zero-cross-database-join queries for **Delivery Operations**, **GraphQL Query API**, and **Communications Service**.

### 1. Operations Implemented

| Operation | Interface ID | Primary Consumers | Purpose & Invariants |
|---|---|---|---|
| `CheckAccountEligibility` | `IF-GRPC-07` | Delivery Operations, GraphQL Query API | Authoritative verification of account, approved merchant, or active courier/staff eligibility. Rejection reasons: `ELIGIBLE`, `ACCOUNT_NOT_FOUND`, `MERCHANT_PENDING`, `MERCHANT_SUSPENDED`, `STAFF_INACTIVE`, `ROLE_MISMATCH`. |
| `GetScopedProfileSummary` | `IF-GRPC-08` | GraphQL Query API, Delivery Operations | Returns minimal profile projection with server-side field scoping (`MINIMAL`, `CUSTOMER`, `MERCHANT`, `STAFF`, `COURIER`). PII (email, private contact) is strictly omitted for unauthorized or cross-tenant callers. |
| `GetBatchProfileSummaries` | `IF-GRPC-09` | GraphQL Query API, Delivery Operations | Bounded batch profile resolution. Request bounded to at most 50 subjects. Restricted to administrative or operational actors (`DISPATCHER`, `ADMIN`). |
| `GetAvailableCouriersSummary` | `IF-GRPC-10` | Delivery Operations, GraphQL Query API | Bounded paginated list of active, eligible couriers with optional zone filtering. Page size bounded between 1 and 50 (default 20). Restricted to `DISPATCHER` or `ADMIN`. |
| `CheckCourierEligibility` | `IF-GRPC-01` | Delivery Operations | Frozen baseline courier eligibility verification. |
| `ValidateMerchantKeyDigest` | `IF-GRPC-02` | Delivery Operations | Frozen baseline merchant API key SHA-256 digest validation with constant-time equality check. |
| `ResolveRecipientContact` | `IF-GRPC-03` | Communications Service | Permitted notification contact resolver with channel scoping (EMAIL/SMS), purpose validation, and fail-closed lifecycle checks (Refs #110). |
| `GetPendingMerchantsSummary` | `IF-GRPC-06` | GraphQL Query API | Frozen baseline admin pending-merchant queue with deterministic pagination. |

### 2. Security & Anti-Spoofing Architecture

Enforced via `SwiftDropGrpcServerSecurityInterceptor` and `AccountsInternalServiceImpl`:
- **Caller Service Authentication:** Each call requires an internal service token (`x-swiftdrop-service-token`) issued by Keycloak with audience `swiftdrop-internal`. Unauthenticated calls fail with gRPC `UNAUTHENTICATED`.
- **Actor Delegation:** Requests acting on behalf of a human principal require verified delegation metadata (`x-swiftdrop-actor-token`, `x-swiftdrop-actor-sub`, `x-swiftdrop-actor-roles`).
- **Strict Anti-Spoofing:** A non-admin/non-dispatcher caller (e.g. `CUSTOMER` or `COURIER`) attempting to pass an arbitrary subject string in `CheckAccountEligibility` or `GetScopedProfileSummary` is rejected with gRPC `PERMISSION_DENIED`. Cross-subject queries fail closed.
- **Permitted Notification Contact Lookup (`IF-GRPC-03`, Refs #110):**
  - Exposes contact lookup strictly to `COMMUNICATIONS_SERVICE` machine identity; zero human actor delegation is permitted (`DelegationRequirement.FORBIDDEN`).
  - Strict input validation on `recipient_sub` (non-blank, bounded <= 255 chars, control character check).
  - Purpose validation against approved notification purposes (`PARCEL_STATUS_UPDATE`, `DELIVERY_NOTIFICATION`, `TRANSACTIONAL`, `ACCOUNT_ALERT`, `NOTIFICATION`).
  - Channel scoping (`EMAIL`, `SMS`, `SMS_MOCK`, `ANY`): `EMAIL` channel strictly suppresses phone number; `SMS` channel strictly suppresses email.
  - Fail-closed lifecycle checks: suspended staff or unapproved merchants yield `is_eligible = false` with non-empty `rejection_reason`. Missing requested contact channel yields `status = "MISSING_CONTACT"` and `is_eligible = false`.
  - Zero PII in logs: recipient email or phone number is never emitted into application logs.
- **Server-Side Field Scoping:** Field visibility is strictly computed server-side:
  - `MINIMAL` scope: Only `subject`, `full_name`, `role`, `status` are populated. Email is **never** returned, even for self.
  - `CUSTOMER` scope: Email is populated **only** if the caller is the verified account owner (`sub == actorSub`) or has `ADMIN` role.
  - `COURIER` / `STAFF` scope: Returns `assignedZone`, `staffId`, `department`. Internal staff emails are never leaked to non-admin actors.
  - Passwords, hashes, Keycloak secrets, and MFA tokens are **never** present in any gRPC protobuf contract.

### 3. Configuration & Ports

| Property | Environment Variable | Default | Description |
|---|---|---|---|
| `grpc.server.port` | `ACCOUNTS_GRPC_PORT` | `9081` | Dedicated gRPC server listen port |
| `grpc.server.enabled` | `ACCOUNTS_GRPC_ENABLED` | `true` | Toggles the gRPC server lifecycle |

### 4. Build & Testing

- **Protobuf Compilation:**
  ```bash
  cd shared/proto-contracts
  ./mvnw clean install
  ```
- **Automated gRPC Tests:**
  `AccountsInternalServiceGrpcTest` executes 37 in-process unit, security, channel scoping, anti-spoofing, and backwards-compatibility test cases.
  ```bash
  cd services/accounts-service
  ./mvnw test -Dtest=AccountsInternalServiceGrpcTest,AccountsGrpcServerLifecycleTest
  ```
- **Full Verification Suite:**
  ```bash
  cd services/accounts-service
  ./mvnw test
  ```
