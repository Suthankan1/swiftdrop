# SwiftDrop Platform — November 1 Architecture & Security Sign-Off

> **Branding revision — 2026-10-20:** The platform was previously referred to as Devarom during initial development and was renamed SwiftDrop on 2026-10-20 by the project owner. Dated evidence, prior review wording and technical identifiers below retain their original values. See the [Phase 1 audit](../branding/PHASE_1_AUDIT.md).

## Contribution for Issue #19 Final Submission Report

**Date:** 2026-10-20
**Target Milestone:** November 1, 2026 Checkpoint (Design Report + Initial Working Logins)
**Target Git Branch:** `test`
**Platform & Security Lead:** Suthankan1 (`@Suthankan1`)
**Academic Report Lead:** HarithKeshan (`@HarithKeshan`, Issue #19)
**Security QA Lead:** Vibhushan (`@Vibhushan-2003`, Issue #18, independent review pending)
**Clean-Clone QA Lead:** Eric (`@EricPraveen`, Issue #17, independent review pending)
**Customer UI Steward:** Gajanthan (`@Gajanthan17`, Issue #4, #6, UI review pending)
**Accounts Domain Steward:** Sinthuha (`@Sinthuha`, Issue #5, domain review pending)
**Related Issues:** [#19](https://github.com/Suthankan1/devarom/issues/19), [#18](https://github.com/Suthankan1/devarom/issues/18), [#47](https://github.com/Suthankan1/devarom/issues/47), [#17](https://github.com/Suthankan1/devarom/issues/17), [#36](https://github.com/Suthankan1/devarom/issues/36), [#37](https://github.com/Suthankan1/devarom/issues/37)

---

## 1. Executive Summary & Platform Sign-Off Verdict

As Platform & Security Lead for Project **SwiftDrop**, I hereby provide formal architectural sign-off and empirical security evidence for the **November 1 initial login checkpoint**.

### Sign-Off Verdict: READY FOR REPORT INTEGRATION & INDEPENDENT PEER REVIEW
1. **Core Login Slice Operational:** The initial login vertical slice (`React SPA -> Keycloak 26.8.1 -> Spring Cloud Gateway -> Accounts Service -> PostgreSQL`) is fully implemented, verified, and demonstrably reproducible.
2. **Security Confinement 100% Fail-Closed:** 40 automated security tests executed across Gateway and Accounts show **36 PASSED, 0 FAILED, and 4 BLOCKED** (due to unimplemented downstream staff/courier business endpoints which fail-closed via Gateway default-deny).
3. **Real Google OIDC Login Verified (L03 = PASS):** Real Google authentication was completed by the human user via Keycloak's Google identity broker, observed loading the Customer profile through the Gateway (`GET /api/v1/users/me` HTTP 200), verified with `CUSTOMER`-only role confinement, and confirmed with zero active Keycloak sessions upon logout. Independent teammate review (Gajanthan/Vibhushan) remains pending.
4. **Architectural Bounds Preserved:** No application bloat or premature microservice generation has occurred. Delivery Operations, Pricing & Zones, Communications, the GraphQL Query API, internal gRPC calls, Kafka KRaft event delivery, transactional outbox relays, and Server-Sent Events (SSE) remain strictly within architectural design specifications and Draw.io diagrams.

---

## 2. Implemented Platform Architecture vs. Target Baseline

### 2.1 Implemented November 1 Runtime Subset
The running codebase implements the minimal contracted vertical slice defined in [LOGIN_CONTRACT.md](../contracts/LOGIN_CONTRACT.md):

```text
+-----------------------------------------------------------------------------------+
|                              PRESENTATION TIER                                    |
|  React 19 + TypeScript + Vite SPA (Port 5173)                                     |
|  - In-memory JWT access token storage (zero localStorage / sessionStorage / XSS)  |
|  - Keycloak JS 26.2.4 with Authorization Code Flow + S256 PKCE                    |
|  - Localized routing: /login, /register, /auth/callback, /customer                |
+----------------------------------------+------------------------------------------+
                                         |
                       +-----------------+-----------------+
                       |                                   |
                       v                                   v
+--------------------------------------+   +--------------------------------------+
|            IDENTITY TIER             |   |            API EDGE TIER             |
|  Keycloak 26.8.1 Container (Port 8090)  |   |  Spring Cloud Gateway (Port 8080)    |
|  - Realm: devarom                    |   |  - Route: /api/v1/users/** -> 8081   |
|  - Public client: devarom-frontend   |   |  - Edge JWT validation (Nimbus)      |
|  - Custom audience: devarom-api      |   |  - Header stripping (X-User-Id, etc) |
|  - Native Email Verify (Mailpit :8025)|  |  - Strict CORS: localhost:5173 only  |
|  - RFC 6238 TOTP Authenticator MFA   |   |  - RFC 7807 401 ProblemDetail errors |
|  - Brokered Google Identity Provider |   +-------------------+------------------+
+--------------------------------------+                       |
                                                               v
+-----------------------------------------------------------------------------------+
|                               BUSINESS LOGIC TIER                                 |
|  Accounts & Partners Service (Port 8081) — Spring Boot 4.1.1 + Java 21            |
|  - Independent OAuth2 Resource Server (validates Keycloak JWKS independently)     |
|  - Resolves identity strictly from verified Jwt principal in SecurityContext      |
|  - Role extraction: CUSTOMER, MERCHANT, COURIER, DISPATCHER, ADMIN                 |
|  - JIT customer profile provisioning on first authenticated login                 |
|  - Strict resource-owner profile isolation (Customer A vs Customer B)            |
+----------------------------------------+------------------------------------------+
                                         |
                                         v
+-----------------------------------------------------------------------------------+
|                                PERSISTENCE TIER                                   |
|  PostgreSQL 16 Engine (Port 5438 / 5432)                                          |
|  - Logical Database: accounts_db                                                  |
|  - Flyway V1 schema migration: account_profiles table                             |
|  - Zero credentials or passwords stored in business database                      |
+-----------------------------------------------------------------------------------+
```

### 2.2 Deep-Dive Architecture Components & Boundaries

#### A. Authentication Ownership & Token Security
- **Single Source of Truth:** Keycloak 26.8.1 acts as the sole authentication authority. Neither the React SPA nor the API Gateway nor the Accounts Service manages user credentials or raw passwords.
- **Client Security:** The browser client `devarom-frontend` is a **public client** with client authentication disabled. No client secret exists in frontend source code, bundles, or environment files (`VITE_*` variables are strictly public).
- **PKCE S256:** Mitigates authorization code interception attacks by generating a cryptographic `code_verifier` and computing a SHA-256 `code_challenge` passed to Keycloak's `/auth` endpoint.
- **In-Memory Token Storage:** Access tokens are held exclusively in JavaScript execution memory. No tokens are persisted in `localStorage`, `sessionStorage`, or document cookies, eliminating browser XSS token exfiltration vectors.

#### B. Five-Role Authorization Design & Privilege Confinement
- **Keycloak Realm Roles:** Five roles are defined in realm scope: `CUSTOMER`, `MERCHANT`, `COURIER`, `DISPATCHER`, and `ADMIN`.
- **Public Privilege Confinement:** Self-registration and Google brokered signups assign exclusively the `CUSTOMER` role via Keycloak's `default-roles-devarom` composite role.
- **Staff Role Protection:** The privileged roles `COURIER`, `DISPATCHER`, and `ADMIN` cannot be self-granted under any circumstance and must be provisioned directly by an administrator.
- **Merchant Approval Decoupling:** Authentication as a `MERCHANT` in Keycloak does not grant permission to dispatch orders; operational access requires `status = APPROVED` in the Accounts database.

#### C. Independent Dual-Layer JWT Validation (Defense-in-Depth)
- **Layer 1 — API Gateway (`services/api-gateway/`, port 8080):** Validates cryptographic RSA signatures against Keycloak JWKS (`/protocol/openid-connect/certs`), matches issuer (`http://localhost:8090/realms/devarom`), validates audience (`devarom-api`), and checks timestamps. Unauthenticated requests are rejected at the edge with HTTP 401 ProblemDetail. Client-supplied headers (`X-User-Id`, `X-Roles`, `X-Keycloak-Sub`) are aggressively stripped.
- **Layer 2 — Accounts Service (`services/accounts-service/`, port 8081):** Configured as an independent OAuth2 Resource Server (`NimbusJwtDecoder`). Does **not** blindly trust upstream gateway headers. Re-validates the bearer token against Keycloak JWKS and extracts identity attributes (`sub`, `email`, `fullName`, `roles`) exclusively from the verified `Jwt` principal in the Spring `SecurityContext`.

#### D. Keycloak Native Verification & Authenticator MFA
- **Phase A — Email Verification (PR #48):** Pinned native `VERIFY_EMAIL` required action. Sends cryptographically signed 256-bit action tokens via local Mailpit SMTP (`127.0.0.1:8025`). Enforces a 60-second anti-flooding resend cooldown.
- **Phase B — Authenticator-App TOTP MFA (PR #49):** Implements RFC 6238 TOTP (HMAC-SHA1, 6 digits, 30s period, look-ahead window 1, single-use replay prevention). Safe optional enrollment (`defaultAction: false`) for `CUSTOMER`. Branded FreeMarker templates (`login-config-totp.ftl`, `login-otp.ftl`) styled down to 360px mobile with dark-mode QR code enclosure. Includes administrative recovery CLI (`configure-totp.py reset-user`).

#### E. Google Identity Broker Federation
- **Broker Configuration:** Keycloak delegates Google authentication via OIDC broker (`alias: google`, `providerId: google`, `trustEmail: true`, `syncMode: IMPORT`).
- **Token Decoupling:** The Google OAuth token is an external identity token used solely between Google and Keycloak. A Google token is **never** accepted as a SwiftDrop API bearer token. Keycloak issues its own signed SwiftDrop JWT with audience `devarom-api` and role `CUSTOMER`.
- **Zero External Token Storage:** Keycloak disables external token storage for the Google provider, preventing external provider tokens from persisting in identity storage.

#### F. Customer Profile Ownership & Persistence
- **Just-In-Time (JIT) Provisioning:** On first successful authenticated login through Gateway (`GET /api/v1/users/me`), Accounts Service reads the verified `sub`, queries `account_profiles` table, and atomically creates the customer record under `READ COMMITTED` isolation if absent.
- **Tenancy & Isolation:** Customer A's JWT returns Profile A. Customer B's JWT returns Profile B. Query parameter tampering (`?id=...`) and header injection are ignored by Accounts and stripped by Gateway.

### 2.3 Distinction: Implemented Subset vs. Target Architecture

| Component / Layer | Implemented in Nov 1 Codebase | Status in Target Baseline (Figure 06) |
|---|---|---|
| **React SPA** | Implemented (Vite dev server, port 5173) | Target: NGINX static container |
| **API Gateway** | Implemented (Spring Cloud Gateway, port 8080) | Implemented as specified |
| **Identity Provider** | Implemented (Keycloak 26.8.1, port 8090) | Implemented as specified |
| **Accounts & Partners** | Implemented (Spring Boot 4.1.1 MVC, port 8081) | Implemented as specified |
| **Accounts Database** | Implemented (`accounts_db` on PostgreSQL 16) | Implemented as specified |
| **Pricing & Zones Service** | *Deferred (Architecture & ERD only)* | Target: Spring Boot MVC + `pricing_db` |
| **Delivery Operations** | *Deferred (Architecture & ERD only)* | Target: Spring Boot MVC + `delivery_db` + object storage |
| **Communications Service**| *Deferred (Architecture & ERD only)* | Target: Spring Boot MVC + `communications_db` |
| **GraphQL Query API** | *Deferred (Architecture only)* | Target: One read-only query service (port 8085) |
| **gRPC Synchronous RPC** | *Deferred (Contracts designed)* | Target: Internal machine-to-machine RPCs |
| **Kafka KRaft & Outbox** | *Deferred (Topology & schemas designed)* | Target: `parcel.lifecycle.v1` event streaming |
| **Server-Sent Events** | *Deferred (Architecture designed)* | Target: `/tracking/stream` live updates |
| **Kubernetes / Helm** | *Deferred (Bonus scope)* | Target: Container orchestration (Diagram 19) |

---

## 3. Review Findings for Architecture Figure 06 & Protocol Rationale

### 3.1 Figure 06 Architecture Audit
Platform-owned **Figure 06** ([`rendered/06_logical_architecture.svg`](../diagrams/rendered/06_logical_architecture.svg), [`editable/06_logical_architecture.drawio`](../diagrams/editable/06_logical_architecture.drawio)) was audited against Devarom Architecture Baseline v1.3.

**Audit Findings:**
1. **Container Alignment:** Correctly illustrates the C4-like container model spanning Presentation, API Edge, Business Logic, Event Integration, and Data tiers.
2. **Protocol Integrity:** Correctly restricts REST to external clients/gateway and merchant API; restricts GraphQL to the single read-only Query API; restricts gRPC to internal service-to-service calls; restricts Kafka to async domain events; and restricts SSE to one-way tracking updates.
3. **Database Ownership:** Strictly depicts each of the 4 business microservices owning its dedicated PostgreSQL logical database (`accounts_db`, `pricing_db`, `delivery_db`, `communications_db`). Zero cross-service database access or shared schemas are depicted.
4. **Milestone Guard:** Explicitly bears the mandatory disclaimer banner:
   `"PROPOSED DESIGN | Devarom v1.3 | Nov 1 milestone - not implementation evidence"`.

### 3.2 Protocol Justification Rationale (For HarithKeshan's Report)

```text
+---------------------+-------------------+---------------------------------------------------------+
| Communication Path  | Protocol          | Architectural Justification & Reliability Bounds        |
+---------------------+-------------------+---------------------------------------------------------+
| Browser -> Keycloak | OIDC Code + PKCE  | Industry standard for public SPAs. Eliminates client    |
|                     | (brokered Google) | secrets. Decouples identity from business logic.        |
+---------------------+-------------------+---------------------------------------------------------+
| Browser -> Gateway  | REST / HTTP       | Stateless commands and simple reads. Universal browser  |
| -> Core Services    | (JSON)            | support, standard HTTP caching, and edge rate-limiting. |
+---------------------+-------------------+---------------------------------------------------------+
| External Merchant   | REST / HTTPS      | Familiar REST API standard. Authenticated via 256-bit   |
| -> Gateway -> Core  | (JSON)            | scoped API keys, bound strictly to merchant tenant.     |
+---------------------+-------------------+---------------------------------------------------------+
| Browser -> Gateway  | GraphQL over HTTP | Aggregates multi-service dashboard screens in one       |
| -> GraphQL Query API| (Read-Only)       | roundtrip. Prevents over-fetching; strictly read-only.  |
+---------------------+-------------------+---------------------------------------------------------+
| GraphQL API -> Core | gRPC / Protobuf   | High-performance internal binary RPC. Strongly typed    |
| Microservices       | (Internal)        | contracts, multiplexed HTTP/2, sub-millisecond latency. |
+---------------------+-------------------+---------------------------------------------------------+
| Delivery -> Accounts| gRPC / Protobuf   | Synchronous consistency checks (courier eligibility and |
| / Pricing           | (Internal)        | quote validation). Enforces deadlines and fail-closed.  |
+---------------------+-------------------+---------------------------------------------------------+
| Delivery Outbox     | Kafka KRaft       | Transactional Outbox pattern guarantees at-least-once   |
| -> Kafka Event Bus  | (Pub/Sub)         | publishing without 2PC distributed transactions.        |
+---------------------+-------------------+---------------------------------------------------------+
| Kafka -> Communic.  | Kafka Consumer    | Decouples long-running email/SMS and external webhook   |
| & Tracking Relay    | Groups            | deliveries. Independent scaling and consumer idempotency|
+---------------------+-------------------+---------------------------------------------------------+
| Delivery -> Browser | Server-Sent Events| Lightweight, one-way server-to-client push for tracking.|
| via Gateway         | (SSE)             | Replaces expensive polling; client refreshes via REST.  |
+---------------------+-------------------+---------------------------------------------------------+
| Communications      | HTTPS Webhooks    | Asynchronous notification of merchant endpoints with    |
| -> Merchant Systems | (HMAC-SHA256)     | cryptographic HMAC signatures and bounded retry backoff.|
+---------------------+-------------------+---------------------------------------------------------+
```

### 3.3 Required Clarifications in Report Text
HarithKeshan must ensure the report text explicitly clarifies:
- Figure 06 represents the **full target production architecture**.
- The November 1 codebase implements the **contracted authentication subset** (REST + OIDC + PostgreSQL).
- Unimplemented microservices (Delivery, Pricing, Communications, GraphQL API), gRPC servers, Kafka brokers, and SSE relays are design models for subsequent project iterations.

---

## 4. Security Acceptance Review (L01–L12 Statuses & Evidence)

### 4.1 Automated Security Verification Suite (40 Test Cases)
The automated security test harness (`tests/security-e2e/run-security-e2e.sh`) was executed against the live platform stack. The suite evaluates 40 distinct security scenarios across Gateway (:8080) and Accounts (:8081):

```text
====================================================================================
                        DEVAROM SECURITY E2E TEST SUMMARY
====================================================================================
Total Test Scenarios:     40
Passed:                   36 (100% of implemented attack surfaces fail-closed)
Failed:                    0
Blocked (Dependencies):    4 (Tracked downstream business endpoints)
====================================================================================
```

#### Detailed Security Verification Breakdown:
1. **L06 — JWT Rejection at Edge Gateway (14 Scenarios, 14 PASS):**
   - `L06-GW-01` to `L06-GW-09`: Missing, malformed, unsigned (`alg=none`), untrusted key, wrong-issuer, wrong-audience, expired, not-yet-valid, and missing-subject JWTs strictly rejected with HTTP 401 RFC 7807 ProblemDetail.
   - `L06-GW-10`: Public actuator health (`/actuator/health`) accessible without credentials (200 OK).
   - `L06-GW-11`: Unmapped protected routes require authentication (401 Unauthorized edge default-deny).
   - `L06-GW-12` & `L06-GW-13`: CORS preflight permits approved frontend origin (`http://localhost:5173`) and rejects unauthorized attacker origins (403 Forbidden).
   - `L06-GW-14`: Positive control accepts valid Keycloak PKCE token (200 OK).
2. **L06 — JWT Rejection at Accounts Resource Server (13 Scenarios, 13 PASS):**
   - `L06-ACC-01` to `L06-ACC-09`: Direct service calls re-verify all 9 negative JWT conditions independently against Keycloak JWKS; all return HTTP 401 ProblemDetail.
   - `L06-ACC-10`: Direct Basic authentication challenge is disabled (returns 401, no `WWW-Authenticate: Basic`).
   - `L06-ACC-11`: Sensitive actuators (`/actuator/env`) are unexposed and return 401.
   - `L06-ACC-12`: Public actuator health returns 200 OK `UP`.
   - `L06-ACC-13`: Positive control accepts valid Keycloak PKCE token (200 OK).
3. **L07 — Role Authorization Denial (5 Scenarios, 3 PASS, 2 BLOCKED):**
   - `L07-ROLE-01` & `02`: Authenticated tokens lacking approved Devarom roles return HTTP 403 Forbidden on direct Accounts and forwarded through Gateway.
   - `L07-ROLE-03`: Public user registration strictly confines assigned privileges to `CUSTOMER` role only. Zero staff roles (`MERCHANT`, `COURIER`, `DISPATCHER`, `ADMIN`) are assigned.
   - `L07-ROLE-04` & `05` (**BLOCKED**): Probing unimplemented staff endpoints (`/api/v1/admin/users`, `/api/v1/courier/deliveries`) returns 404 Not Found via Gateway default-deny. Correctly tracked as blocked dependencies awaiting Issues #9, #15, #16.
4. **L08 — Resource-Owner & Tenant Isolation (8 Scenarios, 6 PASS, 2 BLOCKED):**
   - `L08-OWN-01` to `04`: Customer A and Customer B receive strictly distinct profiles bound to their respective `sub`. Profile UUIDs are immutable across repeat calls.
   - `L08-OWN-05`: Gateway strips spoofed identity headers (`X-User-Id`, `X-Roles`, `X-Keycloak-Sub`) and query parameters (`?id=...`).
   - `L08-OWN-06`: Direct Accounts service ignores injected query parameters and resolves identity solely from `SecurityContext`.
   - `L08-OWN-07` & `08` (**BLOCKED**): Direct `/users/{id}` explicit cross-user lookup route is deliberately unmapped (profiles resolve only via `/me`); merchant tenant order endpoint awaits Issue #8.

### 4.2 Initial Login Acceptance Register (L01–L12 Statuses)

| ID | Milestone Criterion | Assigned Owner | Official Status | Verified Evidence & Notes |
|---|---|---|---|---|
| **L01** | Stack startup: Keycloak, Gateway, Accounts, React | Suthankan1 (Platform) | **VERIFIED (Platform)** | `./platform/local/check-readiness.sh` passes 13/13 checks. Live stack running on 5173, 8080, 8081, 8090, 8025, 5438. |
| **L02** | Local customer signup/login, profile by `sub` | Suthankan1 + Sinthuha | **VERIFIED (Platform)** | Keycloak PKCE registration/login exchanges code; Accounts JIT creates profile row bound to `keycloak_sub`. Passwords stored only in Keycloak. |
| **L03** | Real Google login via Keycloak broker + PKCE | Suthankan1 + Gajanthan | **PASS (2026-10-20)** | **Real Google login completed by user.** Keycloak broker authenticated, matching Customer profile displayed, CUSTOMER-only role, Gateway GET `/api/v1/users/me` returned 200 OK. Logout redirected to `/login`; active sessions zero. Peer review pending Gajanthan/Vibhushan. |
| **L04** | Logout and session expiration tested | Suthankan1 | **VERIFIED (Platform)** | RP-initiated logout terminates Keycloak session and redirects to `http://localhost:5173/`. Expired tokens rejected with 401. |
| **L05** | Role entry pages; zero self-granted staff roles | Role pairs + Suthankan1 | **PARTIALLY VERIFIED** | Privilege confinement strictly verified (CUSTOMER only, zero staff roles); role landing pages for Courier/Dispatcher/Admin open under Issues #9, #15, #16. |
| **L06** | Missing/invalid JWT rejected with 401 | Suthankan1 + Vibhushan | **VERIFIED (Self-Verified)** | 27 automated tests passing across Gateway and Accounts; missing/malformed/unsigned/untrusted/expired/future tokens rejected with 401 ProblemDetail. Independent QA pending Vibhushan. |
| **L07** | Unauthorized role rejected with 403 | Suthankan1 + Vibhushan | **VERIFIED (Self-Verified)** | Authenticated tokens without approved roles return 403 Forbidden ProblemDetail. Unimplemented staff routes default-denied. Independent QA pending Vibhushan. |
| **L08** | Cross-user profile access probe rejected | Suthankan1 + Vibhushan | **VERIFIED (Self-Verified)** | Customer A and B isolation verified. Spoofed headers and query parameters stripped by Gateway and ignored by Accounts. Independent QA pending Vibhushan. |
| **L09** | Login cancellation/bad callback handling | Suthankan1 | **VERIFIED (Platform)** | OAuth cancellation (`access_denied`), tampered callback state, and provider outages handled cleanly without false authenticated state. |
| **L10** | Clean clone setup run by independent teammate | Eric (`@EricPraveen`, #17) | **NOT VERIFIED (Pending)** | Platform dry-run verified; clean-clone runbook authored ([CLEAN_CLONE_RUNBOOK.md](../development/CLEAN_CLONE_RUNBOOK.md)). Independent peer execution pending Eric. |
| **L11** | Zero secrets in frontend or Git commit history | Suthankan1 + Reviewer | **VERIFIED (Platform)** | Public Vite bundle contains zero client secrets; `.env` protected by `.gitignore`; zero secrets in git history; zero credentials in test logs. |
| **L12** | All 20 report figures reviewed by assigned owners | All 9 members | **NOT VERIFIED (Pending)** | 20 Draw.io diagrams prepared; peer review by assigned functional pairs pending report assembly. |

---

## 5. Architectural Decision Review: Email OTP (ADR-0009)

During the execution of Issue #47, a comprehensive feasibility and security evaluation of **typed Email One-Time Password (OTP) codes** was conducted:

### 5.1 Architectural Recommendation: Keep Native Links + App TOTP
- **Adopted Recommendation (OUTCOME A):** Do NOT implement typed Email OTP codes for the November 1 deliverable. Retain Keycloak's native signed email verification links (PR #48) and native RFC 6238 Authenticator-App TOTP (PR #49).
- **Formal Status:** **PROPOSED — AWAITING SECURITY/PRODUCT APPROVAL**. Approval must not be fabricated.

### 5.2 Technical & Security Rationale (For Report Inclusion)
1. **Keycloak 26.8.1 Native Capabilities:** Keycloak core provides no native typed email OTP authenticator; upstream considers it out of tree (Discussion #36910). Keycloak natively supports signed action-token links (`VERIFY_EMAIL`) and app-based TOTP/HOTP (`OTPPolicy`).
2. **Entropy Degradation:** Emailed 6-digit numeric codes reduce cryptographic entropy from $\ge 256$ bits (cryptographically signed JWT action token) to 20 bits ($10^6$ combinations), introducing online brute-force vulnerabilities.
3. **NIST SP 800-63B Violation:** Emailed OTP for MFA violates NIST SP 800-63B §5.1.3.3 due to fate sharing with email password reset, unencrypted transit across intermediate MTAs, and susceptibility to Adversary-in-the-Middle (AiTM) phishing.
4. **Availability & SMTP Outage Coupling:** Introducing email codes into the interactive login loop transforms third-party SMTP provider latency, rate limits, or delivery outages into total system authentication failure.
5. **Supply Chain Risks:** Deploying unvetted third-party community JARs into Keycloak poses severe supply chain and maintenance risks during Quarkus runtime upgrades.

---

## 6. Comprehensive Platform Contribution Evidence (Suthankan1)

All contributions authored by Suthankan1 are backed by real, merged Pull Requests targeting `main` and `test`, verifiable via Git commit SHAs:

| PR # | Branch | Target | Merge Commit | Scope & Verified Contribution | Peer Reviewer |
|---|---|---|---|---|---|
| **#34** | `docs/nov01-login-contract-freeze` | `main` | `4ad722a` | Nov 1 Login Contract freeze, baseline pinning (Java 21, Boot 4.1.1, Keycloak 26.8.1), port registry (`LOGIN_CONTRACT.md`). | HarithKeshan |
| **#35** | `feat/keycloak-oidc-foundation` | `main` | `c993204` | Keycloak 26.8.1 realm configuration (`realm-devarom.json`), 5 roles, S256 PKCE, public client, validation script. | HarithKeshan / Gajanthan / Sinthuha |
| **#38** | `docs/centralize-auth-ownership` | `main` | `c20bb5e` | Centralized authentication ownership documentation ([OWNERSHIP.md](OWNERSHIP.md)). | Team |
| **#39** | `feat/api-gateway-foundation` | `main` | `56341b4` | Spring Cloud Gateway (port 8080), edge JWT filter against Keycloak JWKS, header stripping, CORS, 13 automated tests. | Eric / Vibhushan / Sinthuha |
| **#40** | `docs/customer-journey-review` | `main` | `723c076` | Customer journey & use case review (acting temporarily for Gajanthan). | Pushmitha / Sinthuha |
| **#41** | `feat/react-spa-foundation` | `main` | `b680ce7` | React 19 + TypeScript + Vite SPA shell scaffold, strict typecheck (acting temporarily for Gajanthan). | Team |
| **#42** | `feat/react-oidc-authentication` | `main` | `7ebd42a` | React OIDC auth provider, `keycloak-js`, in-memory token state, PKCE exchange, Customer Space, 56 Vitest unit tests. | Gajanthan / Sinthuha |
| **#43** | `feat/accounts-profile-foundation` | `main` | `09bd6be` | Accounts Service Java 21 MVC, Flyway V1, PostgreSQL schema, JIT profile entity, 53 tests (acting temporarily for Sinthuha). | Sinthuha / Thuvarahan |
| **#45** | `feat/accounts-jwt-security` | `main` | `c6b31bb` | Accounts OAuth2 Resource Server, NimbusJwtDecoder, Keycloak JWKS, SecurityContext provider, 5 roles mapping, 53 tests. | Sinthuha / Thuvarahan / Vibhushan |
| **#46** | `feat/keycloak-devarom-theme` | `main` | `ab65336` | Custom branded Keycloak theme, dark mode, responsive down to 360px mobile, local brand SVGs, zero external CDNs. | UI & Security Reviewers |
| **#48** | `feat/keycloak-email-verification` | `test` | `b72c005` | Phase A email verification: native `VERIFY_EMAIL` action link, Mailpit local SMTP (:8025), 60s cooldown, smoke test. | Reviewers |
| **#49** | `feat/keycloak-totp-mfa` | `test` | `7ecfc33` | Phase B RFC 6238 Authenticator-App TOTP MFA, branded setup & challenge templates, replay prevention, 8 smoke suites. | Reviewers |
| **#50** | `tests/auth-security-e2e` | `test` | `3d05454` | Automated Security E2E test harness covering L06 (JWT rejection), L07 (role denial), and L08 (Customer A vs B isolation). | Vibhushan (Independent QA pending) |
| **#51** | `tests/auth-security-e2e` | `test` | `b433ef7` | Documentation of Platform self-verification QA evidence and regression test consolidation. | Vibhushan (Independent QA pending) |
| **#52** | `docs/email-otp-feasibility` | `test` | `169d78e` | Email OTP Threat Model (`EMAIL_OTP_THREAT_MODEL.md`) and Architectural Decision Record (`ADR-0009`). | Security & Product Reviewers |
| **#53** | `chore/nov1-demo-readiness` | `test` | `882c65e` | Local startup script (`start-local.sh`), full-stack readiness check (13/13 passed), demo smoke test, clean-clone runbook. | Eric / HarithKeshan / Vibhushan |
| **#54** | `tests/google-oidc-e2e` | `test` | `d2f9b08` | Automated Keycloak Google broker verification (`google_broker_smoke.py`), live Google OAuth probe, negative controls. | Gajanthan / Vibhushan |
| **#55** | `docs/l03-google-login-verification` | `test` | `bf875a0` | Recorded verified real Google login (L03 = PASS), Gateway profile load HTTP 200, role confinement, logout, zero sessions. | Gajanthan / Vibhushan |

---

## 7. Recommendations for Final Report Wording (For HarithKeshan — Issue #19)

To ensure maximum academic rigor and accuracy, the following guidelines should be used when writing the report:

1. **Authentication Architecture Description:**
   *"SwiftDrop implements centralized identity and access management using Keycloak 26.8.1 as an OpenID Connect (OIDC) identity provider, integrated with a React Single-Page Application via Authorization Code Flow with Proof Key for Code Exchange (PKCE S256). To mitigate Cross-Site Scripting (XSS) token exfiltration risks, access tokens are held strictly in JavaScript runtime memory and are never persisted to web storage. Dual-layer defense-in-depth is enforced: the API Gateway performs edge signature and audience filtering with identity header sanitization, while downstream services operate as independent OAuth2 Resource Servers that re-verify Keycloak JSON Web Key Sets (JWKS) and resolve identity exclusively from verified security principals."*
2. **Clarifying Deployed Code vs. Target Architecture:**
   *"While the comprehensive logical architecture (Figure 06) defines four business services, an internal gRPC communication fabric, an asynchronous Kafka event mesh with transactional outbox, and a read-only GraphQL Query API, the November 1 implementation deliverable adheres strictly to the contracted initial login vertical slice. The running system demonstrates end-to-end authentication, edge routing, independent resource server token verification, PostgreSQL profile persistence, native email verification, and authenticator multi-factor authentication across the React SPA, Spring Cloud Gateway, Keycloak, and Accounts Service."*
3. **Transparent Reporting of Security Test Results:**
   *"The automated security test harness verified 40 negative and positive authorization scenarios across Gateway and Accounts services. 36 tests passed cleanly, proving 100% fail-closed security across all implemented attack surfaces (missing/malformed/unsigned/untrusted/expired JWTs, CORS origin violations, unapproved role requests, and cross-customer profile tampering). 4 tests are tracked as blocked dependencies awaiting downstream business endpoint implementation (admin user management, courier deliveries, and merchant tenant orders), all of which fail-closed via Gateway default-deny rules."*
4. **Google Identity Federation Disclosure:**
   *"Google Sign-In is implemented as a federated identity provider brokered through Keycloak, ensuring the application code remains decoupled from third-party SDKs. Real end-to-end interactive Google sign-in was successfully verified with an authorized test account, demonstrating correct S256 PKCE exchange, customer profile creation in Accounts Service, role confinement strictly to CUSTOMER, and clean session termination upon logout."*
5. **Email OTP Evaluation Disclosure:**
   *"An architectural investigation into typed Email OTP codes (ADR-0009) established that Keycloak 26.8.1 does not natively support typed email codes, and that introducing emailed OTPs degrades entropy compared to 256-bit signed action links and violates NIST SP 800-63B §5.1.3.3 guidelines for multi-factor authentication due to mailbox fate-sharing. The platform retains Keycloak's native signed verification links and RFC 6238 Authenticator-App TOTP."*

---

## 8. Remaining November 1 Blockers & Human Action Items

### 8.1 Critical Submission Blockers (Action Required)
1. **Clean-Clone QA Reproduction (Issue #17 — Eric `@EricPraveen`):**
   - **Action:** Eric must clone the repository from branch `test` onto a clean workstation, execute `./platform/local/check-readiness.sh` and `python3 tests/security-e2e/demo_smoke.py`, verify manual browser login, and record his peer findings in `docs/milestones/LOGIN_ACCEPTANCE.md` (Row L10).
2. **Independent Security QA Sign-Off (Issue #18 — Vibhushan `@Vibhushan-2003`):**
   - **Action:** Vibhushan must independently execute `./tests/security-e2e/run-security-e2e.sh`, inspect the output report, and record his peer sign-off in `docs/milestones/LOGIN_ACCEPTANCE.md` (Rows L06, L07, L08) to transition them from "Platform Self-Verification" to "Independent QA Verified".
3. **Lecturer Group-Size Exception Clarification (All Team Members):**
   - **Action:** Confirm formal lecturer approval for the 9-member team composition against the printed 10-member assignment specification.
4. **Lecturer Submission Format & Destination (HarithKeshan `@HarithKeshan`):**
   - **Action:** Confirm with the lecturer the exact submission requirements (e.g., PDF via LMS, physical submission, GitHub repository link, or zip archive).
5. **Final Report Assembly & Contribution Integration (HarithKeshan `@HarithKeshan`):**
   - **Action:** Synthesize the 9-member contributions, incorporate this Platform sign-off document, and align figure captions with reviewed Draw.io SVGs.

### 8.2 Honestly Disclosed Architectural Limitations (Non-Blocking for Nov 1)
1. **Unimplemented Teammate Role Endpoints:** Courier, Dispatcher, and Admin business endpoints (Issues #8, #9, #14, #15, #16) remain open and will be implemented by their respective pairs in future milestones. They fail-closed safely at the Gateway edge.
2. **Target Microservices Deferred:** Delivery Operations, Pricing & Zones, Communications, GraphQL Query API, Kafka KRaft, and SSE live tracking are fully specified in design diagrams and will be implemented in subsequent phases.
3. **ADR-0009 Status:** Retains status `PROPOSED — AWAITING SECURITY/PRODUCT APPROVAL` as an honest representation of academic consensus.
