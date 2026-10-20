# SwiftDrop — Security E2E Test Automation & QA Verification

> **Branding revision — 2026-10-20:** The platform was previously referred to as Devarom during initial development and was renamed SwiftDrop on 2026-10-20 by the project owner. Dated evidence, prior review wording and technical identifiers below retain their original values. See the [Phase 1 audit](../../docs/branding/PHASE_1_AUDIT.md).

**Task:** Issue #18 (`[NOV-14] Prove JWT, cross-role and resource-owner denial end to end`)
**Platform & Auth Owner:** Suthankan1
**Temporary QA Execution Owner:** Suthankan1 (Platform Self-Verification)
**Original Intended QA Owner:** Vibhushan (@Vibhushan-2003) (Independent Review Pending)
**Target Integration Branch:** `test`
**Architecture Baseline:** SwiftDrop v1.3

---

## 1. Overview & Objective

This test harness provides reproducible, automated end-to-end security verification proving that:
1. **L06 — JWT Authentication Rejection:** Unauthenticated requests, malformed tokens, untrusted signatures, wrong issuers, wrong audiences, expired tokens, not-yet-valid tokens, missing subjects, and invalid authentication schemes are strictly rejected with HTTP 401 Problem Details at **both** Spring Cloud Gateway (`:8080`) and Accounts Service (`:8081`).
2. **L07 — Cross-Role Authorization Denial:** Authenticated tokens lacking approved SwiftDrop application roles (`CUSTOMER`, `MERCHANT`, `COURIER`, `DISPATCHER`, `ADMIN`) are rejected with HTTP 403 Forbidden. Public self-registration is strictly confined to the `CUSTOMER` role, preventing self-elevation to staff privileges. Unimplemented staff operations are verified as default-denied and documented as blocked dependencies.
3. **L08 — Resource-Owner & Tenant Isolation:** Using authentic Keycloak-issued tokens for distinct synthetic users (Customer A and Customer B), the suite proves that Customer A receives only Customer A's persisted profile, Customer B receives only Customer B's persisted profile, and client-forged identity headers (`X-User-Id`, `X-Keycloak-Sub`, `X-Roles`, `X-User-Email`) and query parameter injections are strictly stripped/ignored without leaking cross-user data.

> **Temporary QA Ownership & Self-Verification Disclosure:**
> The original Issue #18 independent QA owner, Vibhushan (@Vibhushan-2003), is temporarily unavailable. Suthankan1 has taken over execution of the remaining security verification tasks.
> Because Suthankan1 also owns the authentication implementation, this work is strictly classified as **PLATFORM SELF-VERIFICATION**, not independent peer QA. Independent peer review remains pending and will be performed later when another member is available. Vibhushan has not executed or approved these test runs.

---

## 2. Architecture & Service Ports

The test harness runs against the live local development stack:

| Component | Port | Technology | Security Role |
|---|---|---|---|
| **API Gateway** | `http://localhost:8080` | Spring Cloud Gateway (Oakwood) | Edge JWT signature/issuer/audience validation, CORS policy, header stripping (`StripUntrustedHeadersGatewayFilter`). |
| **Accounts Service** | `http://localhost:8081` | Spring Boot 4.1.1 MVC | Independent OAuth2 Resource Server (`NimbusJwtDecoder`), role extraction, trusted identity resolution from `SecurityContext`, profile persistence. |
| **Keycloak IdP** | `http://localhost:8090` | Keycloak 26.8.1 Container | Central OIDC Identity Provider, Authorization Code + S256 PKCE, email verification, TOTP MFA policy. |
| **PostgreSQL** | `localhost:5438` | PostgreSQL 16 Alpine | Dedicated `accounts_db` database mapped to host port **5438** (discovered from environment). |
| **Mailpit** | `http://localhost:8025` | Mailpit v1.27.4 Container | Local SMTP capture for email verification. |
| **React SPA** | `http://localhost:5173` | React 19 + TypeScript + Vite | Browser SPA shell with in-memory token storage (tested via Vitest and RTL). |

---

## 3. Prerequisites & Local Setup

### 3.1 Start Infrastructure Containers

```bash
# 1. Start Keycloak and Mailpit containers
docker compose -f platform/identity/docker-compose.identity.yml up -d

# 2. Start Accounts PostgreSQL container (mapped to host port 5438)
docker start devarom-accounts-postgres
```

### 3.2 Start Backend Microservices

In terminal 1 (Accounts Service):
```bash
cd services/accounts-service
export ACCOUNTS_DB_URL=jdbc:postgresql://localhost:5438/accounts_db
export ACCOUNTS_DB_USERNAME=accounts_app
export ACCOUNTS_DB_PASSWORD=accounts_dev_password
./mvnw spring-boot:run
```

In terminal 2 (API Gateway):
```bash
cd services/api-gateway
export ACCOUNTS_SERVICE_URL=http://localhost:8081
./mvnw spring-boot:run
```

Verify service health:
```bash
curl -i http://localhost:8080/actuator/health   # HTTP 200, status UP
curl -i http://localhost:8081/actuator/health   # HTTP 200, status UP
```

---

## 4. How to Execute the Security E2E Test Suite

### Option A: Master Orchestrator (Recommended for QA)

Runs preflight health checks, the Python security E2E suite, and all regression suites (Identity, TOTP, Gateway, Accounts, Frontend):

```bash
./tests/security-e2e/run-security-e2e.sh
```

### Option B: Standalone Security E2E Runner

Runs only the focused L06, L07, and L08 security tests:

```bash
cd tests/security-e2e
python3 security_e2e_runner.py
```

Results are saved to `tests/security-e2e/reports/security_e2e_results.json`.

---

## 5. Security Test Matrix & Verified Results

### 5.1 Suite 1: L06 — JWT Authentication Rejection (Gateway :8080 & Accounts :8081)

All negative cases assert HTTP 401 Unauthorized with RFC 7807 `application/problem+json`.

| ID | Service | Request | Identity / Role | Expected Status | Actual Status | Positive Control | Result |
|---|---|---|---|---|---|---|---|
| `L06-GW-01` | Gateway | `GET /api/v1/users/me` | Anonymous (No Token) | 401 | 401 | Valid PKCE Token -> 200 OK | **PASS** |
| `L06-GW-02` | Gateway | `GET /api/v1/users/me` | Malformed Token | 401 | 401 | Valid PKCE Token -> 200 OK | **PASS** |
| `L06-GW-03` | Gateway | `GET /api/v1/users/me` | Unsigned (`alg: none`) | 401 | 401 | Valid PKCE Token -> 200 OK | **PASS** |
| `L06-GW-04` | Gateway | `GET /api/v1/users/me` | Untrusted RSA Key | 401 | 401 | Valid PKCE Token -> 200 OK | **PASS** |
| `L06-GW-05` | Gateway | `GET /api/v1/users/me` | Wrong Issuer (`evil.com`) | 401 | 401 | Valid PKCE Token -> 200 OK | **PASS** |
| `L06-GW-06` | Gateway | `GET /api/v1/users/me` | Wrong Audience | 401 | 401 | Valid PKCE Token -> 200 OK | **PASS** |
| `L06-GW-07` | Gateway | `GET /api/v1/users/me` | Expired Token | 401 | 401 | Valid PKCE Token -> 200 OK | **PASS** |
| `L06-GW-08` | Gateway | `GET /api/v1/users/me` | Not-Yet-Valid (`nbf` in future) | 401 | 401 | Valid PKCE Token -> 200 OK | **PASS** |
| `L06-GW-09` | Gateway | `GET /api/v1/users/me` | Missing Subject (`sub` absent) | 401 | 401 | Valid PKCE Token -> 200 OK | **PASS** |
| `L06-GW-10` | Gateway | `GET /actuator/health` | Anonymous | 200 | 200 | Health status UP | **PASS** |
| `L06-GW-11` | Gateway | `GET /api/v1/unmapped` | Anonymous | 401 | 401 | Protected edge denies access | **PASS** |
| `L06-GW-12` | Gateway | `OPTIONS /api/v1/users/me` | Origin `http://localhost:5173` | 200 | 200 | CORS allowed headers returned | **PASS** |
| `L06-GW-13` | Gateway | `OPTIONS /api/v1/users/me` | Origin `http://attacker.evil.com`| 200 | 403 | Disallowed origin rejected | **PASS** |
| `L06-GW-14` | Gateway | `GET /api/v1/users/me` | Authentic Keycloak PKCE Token | 200 | 200 | Profile returns 200 OK | **PASS** |
| `L06-ACC-01`| Accounts| `GET /api/v1/users/me` | Direct Anonymous (No Token) | 401 | 401 | Direct Token -> 200 OK | **PASS** |
| `L06-ACC-02`| Accounts| `GET /api/v1/users/me` | Direct Malformed Token | 401 | 401 | Direct Token -> 200 OK | **PASS** |
| `L06-ACC-03`| Accounts| `GET /api/v1/users/me` | Direct Unsigned Token | 401 | 401 | Direct Token -> 200 OK | **PASS** |
| `L06-ACC-04`| Accounts| `GET /api/v1/users/me` | Direct Untrusted Key | 401 | 401 | Direct Token -> 200 OK | **PASS** |
| `L06-ACC-05`| Accounts| `GET /api/v1/users/me` | Direct Wrong Issuer | 401 | 401 | Direct Token -> 200 OK | **PASS** |
| `L06-ACC-06`| Accounts| `GET /api/v1/users/me` | Direct Wrong Audience | 401 | 401 | Direct Token -> 200 OK | **PASS** |
| `L06-ACC-07`| Accounts| `GET /api/v1/users/me` | Direct Expired Token | 401 | 401 | Direct Token -> 200 OK | **PASS** |
| `L06-ACC-08`| Accounts| `GET /api/v1/users/me` | Direct Not-Yet-Valid Token | 401 | 401 | Direct Token -> 200 OK | **PASS** |
| `L06-ACC-09`| Accounts| `GET /api/v1/users/me` | Direct Missing Subject | 401 | 401 | Direct Token -> 200 OK | **PASS** |
| `L06-ACC-10`| Accounts| `GET /api/v1/users/me` | Direct Basic Auth Header | 401 | 401 | Basic disabled, no modal | **PASS** |
| `L06-ACC-11`| Accounts| `GET /actuator/env` | Direct Anonymous | 401 | 401 | Unexposed actuator denied | **PASS** |
| `L06-ACC-12`| Accounts| `GET /actuator/health`| Direct Anonymous | 200 | 200 | Direct health returns UP | **PASS** |
| `L06-ACC-13`| Accounts| `GET /api/v1/users/me` | Direct Authentic Keycloak Token| 200 | 200 | Direct profile returns 200 OK| **PASS** |

### 5.2 Suite 2: L07 — Cross-Role Authorization Denial

| ID | Service | Request | Identity / Role | Expected Status | Actual Status | Positive Control | Result |
|---|---|---|---|---|---|---|---|
| `L07-ROLE-01` | Accounts | `GET /api/v1/users/me` | Authenticated (No Devarom Role) | 403 | 403 | CUSTOMER role receives 200 OK | **PASS** |
| `L07-ROLE-02` | Gateway | `GET /api/v1/users/me` | Authenticated (No Devarom Role) | 403 | 403 | Downstream 403 forwarded cleanly | **PASS** |
| `L07-ROLE-03` | Keycloak | GET role-mappings/composite | Self-Registered Customer | 200 | 200 | Contains CUSTOMER; zero staff roles | **PASS** |
| `L07-ROLE-04` | Gateway | `GET /api/v1/admin/users` | Authenticated CUSTOMER | 403 | 404 | Admin operations require ADMIN role | **BLOCKED** |
| `L07-ROLE-05` | Gateway | `GET /api/v1/courier/deliveries` | Authenticated CUSTOMER | 403 | 404 | Courier operations require COURIER | **BLOCKED** |

> **Blocked Dependency Note on L07-ROLE-04 & L07-ROLE-05:** Staff business read models and administrative operation endpoints are open under Issues #14, #15, and #16 (owned by HarithKeshan, Pushmitha, Abhinaya, and Vibhushan). The Gateway edge policy enforces default-deny (HTTP 404 for unrouted paths; 401 if unauthenticated). As mandated by project rules, fake endpoints were NOT created.

### 5.3 Suite 3: L08 — Resource Ownership & Tenant Isolation

| ID | Service | Request | Identity / Role | Expected Status | Actual Status | Positive Control | Result |
|---|---|---|---|---|---|---|---|
| `L08-OWN-01` | GW & Accounts | `GET /api/v1/users/me` | Customer A (Token A) | 200 | 200 | Returns Customer A profile bound to Subject A | **PASS** |
| `L08-OWN-02` | GW & Accounts | `GET /api/v1/users/me` | Customer B (Token B) | 200 | 200 | Returns Customer B profile bound to Subject B | **PASS** |
| `L08-OWN-03` | Accounts | Compare Profile A vs B | Multi-tenant Customer | 200 | 200 | Profile A UUID != Profile B UUID | **PASS** |
| `L08-OWN-04` | Accounts | Repeat `GET /users/me` | Customer A (Repeat) | 200 | 200 | Immutable profile ID preserved across requests | **PASS** |
| `L08-OWN-05` | GW & Accounts | `GET /api/v1/users/me` (Spoofed Headers) | Customer A forging Customer B headers | 200 | 200 | Gateway strips headers; Customer A data returned | **PASS** |
| `L08-OWN-06` | Accounts | Direct `GET /users/me` (Spoofed Headers) | Customer A forging Customer B headers | 200 | 200 | Accounts strictly uses JWT context; Customer A data returned | **PASS** |
| `L08-OWN-07` | Accounts | `GET /api/v1/users/{Customer_B_ID}` | Customer A attempting IDOR | 403 | 404 | Only `/api/v1/users/me` exists; default-deny | **BLOCKED** |
| `L08-OWN-08` | Accounts | `GET /api/v1/merchants/orders` | Customer A attempting cross-tenant access | 403 | 404 | Merchant tenancy provider enforces boundary | **BLOCKED** |

> **Blocked Dependency Note on L08-OWN-07 & L08-OWN-08:** Accounts Service intentionally does NOT provide a public `/api/v1/users/{id}` endpoint (avoiding IDOR vulnerabilities by design; profiles are resolved exclusively via the trusted JWT subject in `GET /api/v1/users/me`). Merchant tenancy provider is open under Issue #8 (Eric & Thuvarahan). Full IDOR and merchant tenant isolation coverage remains marked as **BLOCKED BY MISSING PROVIDER ENDPOINT** rather than fabricated.

---

## 6. Cleanup & Security Invariants

1. **Synthetic Identity Teardown:** The test runner automatically deletes all disposable test users (`dv-sec-*`) from Keycloak via the Admin REST API in a `finally` block upon completion.
2. **Zero Destructive Resets:** The database is never dropped or truncated. Profiles created during positive tests remain safely bound to their synthetic subjects without interfering with shared data.
3. **Zero Committed Secrets:** No passwords, access tokens, client secrets, or private keys are written to source files, JSON reports, or Git history.
4. **No Token Dumping in Logs:** Test logs and JSON reports never print Authorization Bearer tokens.

---

## 7. Independent QA Review Procedure (For Vibhushan)

To reproduce and independently sign off on these security tests:

1. Clone or checkout the branch:
   ```bash
   git switch test
   git pull --ff-only origin test
   git switch tests/auth-security-e2e
   ```
2. Ensure Docker containers are running:
   ```bash
   docker ps
   # Verify devarom-keycloak (8090), devarom-mailpit (8025), and devarom-accounts-postgres (5438)
   ```
3. Start Accounts Service and API Gateway in separate terminals as described in Section 3.
4. Run the orchestrator:
   ```bash
   ./tests/security-e2e/run-security-e2e.sh
   ```
5. Confirm the console matrix matches:
   - Total: 40 tests
   - Passed: 36 tests
   - Failed: 0 tests
   - Blocked: 4 tests (documented dependencies)
6. Record your independent review in `docs/milestones/LOGIN_ACCEPTANCE.md` and Issue #18.
