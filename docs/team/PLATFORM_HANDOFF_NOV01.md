# SwiftDrop Platform — November 1 Milestone Submission & QA Handoff

> **Branding revision — 2026-10-20:** The platform was previously referred to as Devarom during initial development and was renamed SwiftDrop on 2026-10-20 by the project owner. Dated evidence, prior review wording and technical identifiers below retain their original values. See the [Phase 1 audit](../branding/PHASE_1_AUDIT.md).

**Target Milestone:** November 1, 2026 Checkpoint (Report + Initial Working Logins)  
**Target Integration Branch:** `test`  
**Platform & Security Lead:** Suthankan1  
**Contributors & Reviewers:**
- **Issue #17 (Clean-Clone QA Reproduction):** Eric (@EricPraveen)
- **Issue #18 (Security E2E Acceptance):** Vibhushan (@Vibhushan-2003) (Independent Review Pending)
- **Issue #19 (Report Assembly & Contribution Integration):** HarithKeshan (@HarithKeshan)

---

## 1. Executive Summary & Architecture Baseline

The SwiftDrop initial-login vertical slice is **feature-complete, fully integrated, and demo-ready** across the contracted core stack:

```text
[React SPA] (5173) ────────► [Spring Cloud Gateway] (8080) ────────► [Accounts Service] (8081)
      │                                   │                                    │
      ▼                                   ▼                                    ▼
[Keycloak IdP] (8090)             [Edge JWT Filter]                    [Resource Server]
  - Branded Theme (v2)              - 401 on bad/expired JWT             - Independent Nimbus JWT
  - Email Verify (Mailpit :8025)    - CORS localhost:5173                - Subject-bound persistence
  - RFC 6238 TOTP MFA               - Header stripping                   - Conflict-safe JIT profile
  - Public PKCE S256 Client                                            - accounts_db (PostgreSQL)
```

### Architectural Bounds Adhered To:
- **Zero Monolithic Bloat:** Only Gateway, Accounts Service, Keycloak, and React SPA are run for this milestone.
- **Future Services Unchanged:** Delivery, Pricing, Communications, Kafka, outbox, and GraphQL query dashboards remain designed in documentation/diagrams for future implementation.
- **Zero Passwords in Business DB:** Accounts Service stores only `keycloak_sub`, name, email, and roles. All credential handling belongs strictly to Keycloak.
- **No Shared Entities:** Services communicate exclusively via HTTP REST with JSON contracts; no shared database entities.

---

## 2. Merged PR Evidence & Commit Provenance

The Platform and Authentication implementation comprises 8 pull requests targeting `test` (and 10 preceding pull requests on `main`; see [PLATFORM_SIGNOFF_NOV01.md](PLATFORM_SIGNOFF_NOV01.md) for complete provenance):

| PR # | Branch | Merged Commit | Scope & Verified Deliverable |
|---|---|---|---|
| **#48** | `feat/keycloak-email-verification` | `b72c005` | Keycloak native email verification link, Mailpit local SMTP (`:8025`), 60s resend cooldown, and automated registration smoke test. |
| **#49** | `feat/keycloak-totp-mfa` | `7ecfc33` | RFC 6238 Authenticator-App TOTP MFA policy, branded setup & challenge templates, single-use replay prevention, and 8 smoke suites. |
| **#50** | `tests/auth-security-e2e` | `3d05454` | Automated Security E2E test harness covering L06 (JWT rejection), L07 (role denial), and L08 (Customer A vs B isolation). |
| **#51** | `tests/auth-security-e2e` | `b433ef7` | Documentation of Platform self-verification results and regression test consolidation. |
| **#52** | `docs/email-otp-feasibility` | `169d78e` | Threat model ([EMAIL_OTP_THREAT_MODEL.md](../security/EMAIL_OTP_THREAT_MODEL.md)) and formal architectural decision ([ADR-0009](../architecture/adr/ADR-0009.md)) for Email OTP. |
| **#53** | `chore/nov1-demo-readiness` | `882c65e` | Reproducible startup helper (`start-local.sh`), full-stack readiness check (`check-readiness.sh`), automated demo runner (`demo_smoke.py`), clean-clone runbook, and team handoffs. |
| **#54** | `tests/google-oidc-e2e` | `d2f9b08` | Automated Keycloak Google broker verification (`google_broker_smoke.py`), live Google OAuth probe, and negative controls. |
| **#55** | `docs/l03-google-login-verification` | `bf875a0` | Recorded verified real Google login (L03 = PASS), Gateway profile load HTTP 200, role confinement, logout, and zero sessions. |

> See [PLATFORM_SIGNOFF_NOV01.md](PLATFORM_SIGNOFF_NOV01.md) for the exhaustive Platform architecture and security sign-off contribution prepared for HarithKeshan's Issue #19 report.


---

## 3. Peer Clean-Clone Verification Handoff (For Eric — Issue #17)

**Task Owner:** Eric (@EricPraveen)  
**Deliverable:** Independently clone the repository on a clean machine/worktree from the `test` branch, execute the startup and readiness sequence, verify browser login, and record evidence for milestone requirement **L10**.

> [!IMPORTANT]
> - Suthankan1 has executed a platform local dry run.
> - **L10 remains UNVERIFIED** until Eric (or an independent peer reviewer) executes the runbook from a clean clone and confirms the results. Do not mark L10 complete without independent peer evidence.

### Step-by-Step Instructions for Eric:

1. **Clone the `test` branch into a fresh folder:**
   ```bash
   git clone -b test https://github.com/Suthankan1/swiftdrop.git swiftdrop-clean
   cd swiftdrop-clean
   ```

2. **Supply local administrator credentials:**
   ```bash
   cp platform/identity/.env.example platform/identity/.env
   # Set an explicit password for KC_BOOTSTRAP_ADMIN_PASSWORD in platform/identity/.env
   ```

3. **Start container infrastructure:**
   ```bash
   ./platform/local/start-local.sh
   ```
   *Starts Keycloak (:8090), Mailpit (:8025), and PostgreSQL (:5438 or :5432).*

4. **Start microservices and frontend in separate terminals:**
   - **Terminal 1:** `cd services/accounts-service && ACCOUNTS_DB_URL=jdbc:postgresql://localhost:5438/accounts_db ACCOUNTS_DB_USERNAME=accounts_app ACCOUNTS_DB_PASSWORD=accounts_dev_password ./mvnw spring-boot:run`
   - **Terminal 2:** `cd services/api-gateway && ACCOUNTS_SERVICE_URL=http://localhost:8081 ./mvnw spring-boot:run`
   - **Terminal 3:** `cd frontend && pnpm install --frozen-lockfile && pnpm dev`

5. **Run the automated readiness checker:**
   ```bash
   ./platform/local/check-readiness.sh
   ```
   *Confirm all 13 checks pass.*

6. **Run the automated login demo smoke test:**
   ```bash
   python3 tests/security-e2e/demo_smoke.py
   ```
   *Exercises new customer registration with Mailpit email verification, existing customer login, TOTP MFA challenge, and security denial controls.*

7. **Perform manual browser inspection:**
   - Open `http://localhost:5173/login`.
   - Test user registration, Mailpit email link click (`http://localhost:8025`), and Customer Space rendering.
   - Test sign-out and session termination.

8. **Sign off on Issue #17:**
   - Record your observed test output, host OS/specs, and confirmation in `docs/milestones/LOGIN_ACCEPTANCE.md` (Row L10) and comment on GitHub Issue #17.

---

## 4. Academic Report & Contribution Handoff (For HarithKeshan — Issue #19)

**Task Owner:** HarithKeshan (@HarithKeshan)  
**Deliverable:** Assemble the November 1 submission report, design rationales, contribution evidence, and test results.

Below are the verified platform inputs ready for direct inclusion in the report:

### 4.1 System Security & Protocol Architecture Rationale
- **Frontend SPA Security:** Single React 19 + TypeScript + Vite SPA. Tokens are kept exclusively in **JavaScript runtime memory**; zero token storage in `localStorage`, `sessionStorage`, or readable cookies, eliminating browser XSS token-exfiltration vectors.
- **Identity & OIDC:** Keycloak 26.8.1 acts as the central IdP. Uses Authorization Code Grant with **PKCE S256**. Public client (`swiftdrop-frontend`) with zero client secrets in browser code (`VITE_*` variables are strictly public).
- **Dual-Layer JWT Verification:**
  - *Layer 1 (API Gateway :8080):* Validates JWT signature against Keycloak JWKS, validates audience (`swiftdrop-api`), issuer, and expiration. Strips all client-supplied identity headers (`X-User-Id`, `X-Roles`, `X-Keycloak-Sub`) to prevent spoofing. Returns RFC 7807 401 ProblemDetail on rejection.
  - *Layer 2 (Accounts Service :8081):* Independent OAuth2 Resource Server (`NimbusJwtDecoder`). Extracts `sub` and roles strictly from verified Spring Security `Jwt` principal in `SecurityContext`. Enforces multi-tenant profile isolation.
- **Native Verification & MFA:**
  - Phase A: Native Keycloak email verification link captured locally via Mailpit (RFC 2822 / 5321) with 60s anti-flooding resend cooldown.
  - Phase B: RFC 6238 Authenticator-App TOTP MFA (HMAC-SHA1, 6 digits, 30s period, single-use replay prevention).
  - Phase C Decision (ADR-0009): Keycloak provides no native typed email OTP authenticator. Threat model proved typed email codes degrade cryptographic entropy (from 256 bits to 20 bits) and violate NIST SP 800-63B §5.1.3.3. Formally decided to retain native signed links and app TOTP.

### 4.2 Security Test Results Summary (Table for Report)

| Security Category | Scenarios Tested | Passed | Failed | Blocked (Tracked Dependencies) | Key Finding |
|---|---|---|---|---|---|
| **L06: JWT Rejection (Gateway :8080)** | 14 | 14 | 0 | 0 | Missing, malformed, unsigned (`alg=none`), untrusted, wrong-issuer, wrong-audience, expired, and future-dated tokens strictly rejected with HTTP 401. |
| **L06: JWT Rejection (Accounts :8081)** | 13 | 13 | 0 | 0 | Direct access independently verifies Keycloak JWKS; Basic auth and unexposed actuators rejected with 401. |
| **L07: Role Authorization Denial** | 5 | 3 | 0 | 2 (Staff endpoints) | Tokens lacking approved roles return 403 Forbidden on direct Accounts and forwarded by Gateway. Public registration strictly confined to `CUSTOMER` role. |
| **L08: Resource-Owner & Tenant Isolation** | 8 | 6 | 0 | 2 (Merchant/IDOR) | Customer A receives only Customer A profile; Customer B receives only Customer B profile. Spoofed headers and query injections stripped/ignored. |
| **Total Security Matrix** | **40** | **36** | **0** | **4** | **100% of implemented attack surfaces fail closed.** |

### 4.3 Blocked Dependency Register (For Report Transparency)
The 4 blocked tests reflect honest tracking of unimplemented teammate features, rather than mock failures:
1. `L07-ROLE-04` (Admin ops denial): Awaits staff read endpoints (Issue #14: HarithKeshan/Abhinaya). Gateway default-denies with 404.
2. `L07-ROLE-05` (Courier ops denial): Awaits courier queue endpoints (Issue #15: Vibhushan/Abhinaya). Gateway default-denies with 404.
3. `L08-OWN-07` (Cross-user `/users/{id}` lookup): Accounts deliberately avoids exposing an explicit ID lookup route; profiles are resolved only via authenticated `sub`.
4. `L08-OWN-08` (Merchant tenancy provider): Awaits merchant approval-state provider (Issue #8: Eric/Thuvarahan).
5. `L03` (Google OAuth): **VERIFIED (PASS)**. Real Google sign-in completed by user and verified through Keycloak broker, Gateway HTTP 200 profile load, CUSTOMER role confinement, and logout with zero sessions (PR #55). Independent peer review pending Gajanthan/Vibhushan.


---

## 5. Independent Security QA Sign-Off (For Vibhushan — Issue #18)

**Task Owner:** Vibhushan (@Vibhushan-2003)  
**Deliverable:** Reproduce and sign off on the 40-test security suite to transition it from "Platform Self-Verification" to "Independent QA Verified".

### Steps for Vibhushan:
1. Ensure the stack is running per the runbook (`./platform/local/check-readiness.sh`).
2. Run the full security orchestrator:
   ```bash
   ./tests/security-e2e/run-security-e2e.sh
   ```
3. Inspect `tests/security-e2e/reports/security_e2e_results.json`.
4. Update `docs/milestones/LOGIN_ACCEPTANCE.md` (Rows L06, L07, L08) to record your peer confirmation.
