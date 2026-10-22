# SwiftDrop — November 1 Milestone Readiness & Team Onboarding Tracker

**Milestone:** November 1, 2026 Checkpoint (Design Report + Initial Working Logins)  
**Status:** ACTIVE TRACKER — REPOSITORY READY FOR TEAM ONBOARDING  
**Target Git Integration Branch:** `test`  
**Current Baseline Commit:** `c11bf89` (PR #70 merged into `main`)  
**Canonical Repository:** `https://github.com/Suthankan1/swiftdrop`  
**Integration Coordinator & Platform Lead:** Suthankan1 (`@Suthankan1`)  
**Academic Report Lead:** HarithKeshan (`@Harithkeshan`, Issue #19)  
**Security QA Lead:** Vibhushan (`@Vibhushan-2003`, Issue #18)  
**Clean-Clone QA Lead:** Eric (`@EricPraveen`, Issue #17)  

---

## 1. Executive Summary & Repository Health

As of October 21, 2026, the SwiftDrop repository has completed all preparatory platform milestones:
- **Phase 1 Branding:** Complete (all documentation, diagram previews, and assets aligned with SwiftDrop).
- **Phase 2 Identity Migration:** Complete (canonical `swiftdrop` realm, `swiftdrop-frontend` client, `swiftdrop-api` audience, custom SwiftDrop theme, email verification, TOTP MFA, and live Google OIDC broker verified).
- **Phase 3 Technical Rebranding:** Complete (Spring Boot Maven artifacts `com.swiftdrop:*`, package roots `com.swiftdrop.*`, Docker Compose project `swiftdrop`, non-destructive container compatibility, and full test suite passing).
- **PR #70 Merged:** Merged cleanly into `main` at commit `c11bf89`.
- **Zero Open PRs:** Working tree is clean; all tests pass.

The platform foundation is frozen and verified. The repository is now ready for the remaining 8 teammates to clone, configure their local environments, execute independent QA, and implement their respective role landing views and report sections.

---

## 2. Git Branch State & Release Synchronization Plan

### 2.1 Branch Divergence Analysis
- **`origin/main` Commit:** `c11bf89` (Merge PR #70)
- **`origin/test` Commit:** `0d44d15` (Merge PR #71)
- **Divergence Count (`git rev-list --left-right --count origin/main...origin/test`):** `0 2`
- **Unique Commits on `origin/test`:** `2` (Commit `2b67001` and merge commit `0d44d15` from PR #71).
- **Working Tree Comparison (`git diff origin/main..origin/test`):** Purely documentation additions (`NOV_01_READINESS_TRACKER.md` and `NOV_01.md`). Zero code differences. Zero merge conflicts.

### 2.2 Release Promotion Plan (Test to Main)
Per [`docs/development/GIT_WORKFLOW.md`](../development/GIT_WORKFLOW.md):
1. PR #71 has been reviewed and merged into `test`, establishing the frozen November 1 readiness baseline.
2. A release PR is prepared targeting base `main` from head `test`:
   - Title: `chore(release): promote SwiftDrop November 1 readiness baseline`
   - Content: Phase 4A readiness tracker, onboarding runbook, QA ownership protocols.
3. **Approval Gate:** The release PR remains open for human review and merge through GitHub. `main` is never pushed to directly or auto-merged.

---

## 3. Team Roster, Ownership & Collaborator Status

The project team consists of 9 members across 5 functional areas.

### 3.1 Collaborator Access & Acceptance Status

| Member | Module Role | GitHub Handle | Repo Collaborator Status | Issue Assignments | Notes / Acknowledgement |
|---|---|---|---|---|---|
| **Suthankan1** | Platform & Security Lead | `@Suthankan1` | **Owner / Admin** | #18 (temp), #26, #28, #30, #33, #47 | Owns shared authentication, Gateway, Accounts JWT, local platform |
| **Gajanthan** | Customer Lead (FE) | `@Gajanthan23` | **Accepted** | #4, #6, #7, #27 | Customer journey, booking UI; consumed platform auth |
| **Sinthuha** | Customer Lead (BE) | `@Sinthuha-n` | **Accepted** | #5, #7, #20 | Accounts business profile, customer data ownership |
| **Eric** | Merchant Lead (FE) | `@EricPraveen` | **Accepted** | #9, #17, #21 | Merchant landing UI, clean-clone independent QA |
| **Thuvarahan** | Merchant Lead (BE) | `@thuvarahan-t` | **Invitation Pending** | #8, #10, #22 | Merchant approval state, scoped API key design |
| **Abhinaya** | Courier Lead (FE) | `@abhi190503` | **Accepted** | #11, #12, #15, #29 | Courier delivery UI, proof-of-delivery capture |
| **Vibhushan** | Courier Lead (BE) & Security QA | `@Vibhushan-2003` | **Invitation Pending** | #18 (Primary QA), #11, #23 | Independent security QA, courier status rules |
| **Pushmitha** | Dispatcher/Admin (FE) | `@pushmitha20` | **Accepted** | #13, #16, #25 | Dispatcher queue UI, Admin role landing |
| **HarithKeshan** | Dispatcher/Admin (BE) & Report Lead | `@Harithkeshan` | **Accepted** | #14, #19, #24 | Academic report assembly, staff profile models |

> [!IMPORTANT]
> **Action Required:** `@thuvarahan-t` and `@Vibhushan-2003` have pending repository invitations. They must accept their invitations at `https://github.com/Suthankan1/swiftdrop/invitations` to finalize collaborator permissions.

---

## 4. Teammate Development Onboarding Runbook

Every teammate must follow these standardized steps when setting up their local workstation.

### Step 1: Install Pinned Toolchain Prerequisites
Verify tools meet the baseline in [`docs/contracts/LOGIN_CONTRACT.md`](../contracts/LOGIN_CONTRACT.md):
- **Git:** 2.40+ (`git --version`)
- **Docker:** 24+ with Compose v2 (`docker compose version`)
- **Java:** **Java 21 LTS** (`export JAVA_HOME=$(/usr/libexec/java_home -v 21)` on macOS)
- **Node.js:** **Node 24 LTS** (or 20+) (`node --version`)
- **pnpm:** **12.4+ / 12.11.2** (`corepack enable` or `npm install -g pnpm`)
- **Python:** 3.10+ (`python3 --version`)

### Step 2: Clone the Integration Branch
```bash
git clone -b test https://github.com/Suthankan1/swiftdrop.git
cd swiftdrop
```

### Step 3: Configure Local Environment Secrets (Outside Git)
```bash
# 1. Identity secrets (Keycloak administrator credentials)
cp platform/identity/.env.example platform/identity/.env

# Edit platform/identity/.env and set your local admin password:
# KC_BOOTSTRAP_ADMIN_PASSWORD=your_secure_password_here

# 2. Frontend public configuration
cp frontend/.env.example frontend/.env
```
> [!CAUTION]
> Never commit `.env` files or share live Keycloak H2 database files. `.gitignore` strictly protects `.env`, `.env.*`, and `secrets/`.

### Step 4: Start Infrastructure Containers
Use the non-destructive startup helper:
```bash
./platform/local/start-local.sh
```
This starts:
- Keycloak 26.8.1 on port `8090` (realm: `swiftdrop`, client: `swiftdrop-frontend`)
- Mailpit SMTP Web UI on port `8025` (internal SMTP: `1025`)
- PostgreSQL 16 on port `5438` (database: `accounts_db`, user: `accounts_app`)
- Automatically applies SwiftDrop theme, email verification policy, and TOTP MFA configuration.

### Step 5: Start Microservices & React SPA
Open 3 separate terminal tabs:
- **Terminal 1 (Accounts Service):**
  ```bash
  cd services/accounts-service
  export ACCOUNTS_DB_URL=jdbc:postgresql://localhost:5438/accounts_db
  export ACCOUNTS_DB_USERNAME=accounts_app
  export ACCOUNTS_DB_PASSWORD=accounts_dev_password
  ./mvnw spring-boot:run
  ```
  *(Listens on port 8081)*

- **Terminal 2 (API Gateway):**
  ```bash
  cd services/api-gateway
  export ACCOUNTS_SERVICE_URL=http://localhost:8081
  ./mvnw spring-boot:run
  ```
  *(Listens on port 8080)*

- **Terminal 3 (React Frontend):**
  ```bash
  cd frontend
  pnpm install --frozen-lockfile
  pnpm dev
  ```
  *(Serves SPA on `http://localhost:5173`)*

### Step 6: Verify Readiness & Run Tests
```bash
# Full toolchain & health check (must return 13 PASS, 0 FAIL)
./platform/local/check-readiness.sh

# Automated smoke test suite
python3 tests/security-e2e/demo_smoke.py

# Frontend unit tests
cd frontend && pnpm test --run && pnpm typecheck && pnpm lint
```

### Step 7: Git Workflow for New Features
1. Update local `test`: `git checkout test && git pull --ff-only origin test`
2. Branch from `test`: `git checkout -b <type>/<short-description>` (e.g., `feat/merchant-landing-ui`)
3. Commit using conventional format: `feat:`, `fix:`, `docs:`, `test:`, `chore:`.
4. Push and open PR targeting base **`test`** on GitHub. Never target `main` directly.
5. Code must preserve canonical packages (`com.swiftdrop.<service>`) and data ownership boundaries.

---

## 5. Independent QA Handoffs

### 5.1 Clean-Clone QA Handoff (Owner: Eric `@EricPraveen`, Issue #17)
**Milestone Acceptance Item:** `L10`  
**Current Status:** **PASSED (INDEPENDENT PEER VERIFICATION COMPLETE — Eric `@EricPraveen`, 2026-10-22)**  
**Official Handoff Comment:** [Issue #17 Comment](https://github.com/Suthankan1/swiftdrop/issues/17#issuecomment-6312161904) posted on 2026-10-21.

#### Executable Peer QA Checklist for Eric (Completed & Verified):
1. **Clone fresh repository on clean workstation:** Executed on commit `466b1fb` (`feat/nov-09-merchant-login-reproduction`) on macOS Darwin arm64. -> **PASSED**
2. **Follow Section 4 of this runbook to configure `.env` and start all containers and services:** Ran `./platform/local/start-local.sh`, Accounts Service on `:8081`, API Gateway on `:8080`, and Frontend SPA on `:5173`. Fixed PostgreSQL port discovery collision and master admin test email requirement. -> **PASSED**
3. **Execute preflight check:** `./platform/local/check-readiness.sh` -> **13/13 PASSED** (0 warnings, 0 failures). -> **PASSED**
4. **Run automated demo smoke:** `python3 tests/security-e2e/demo_smoke.py` -> **4/4 Scenarios PASSED** (A: Self-Registration & Email Verification; B: Verified Customer Login & Profile; C: TOTP MFA Challenge; D: Security Denials & Boundary Controls). -> **PASSED**
5. **Interactive browser verification at `http://localhost:5173`:**
   - Click "Create account" -> Register `test.eric@swiftdrop.test`. -> **PASSED**
   - Verify Keycloak shows email verification required notice. -> **PASSED**
   - Open `http://localhost:8025` (Mailpit) -> Click verification link in received email. -> **PASSED**
   - Complete password confirmation. -> **PASSED**
   - Confirm browser redirects to `/customer` and displays Customer Space with role `CUSTOMER`. -> **PASSED**
   - Verify role boundary guard: attempt navigation to `/merchant` -> Intercepted, displays `<FeedbackState>` access denial for CUSTOMER role. -> **PASSED**
   - Click "Sign out" -> Confirm redirected to home `/` and in-memory tokens wiped. -> **PASSED**
6. **Record actual commit SHA, OS/hardware details, terminal commands, and observed results in GitHub Issue #17:** Recorded in `docs/milestones/LOGIN_ACCEPTANCE.md`, `docs/project/STATUS.md`, and Issue #17 report. -> **PASSED**
7. **Rule:** `L10` officially marked as **PASS** with independent evidence. -> **PASSED**

---

### 5.2 Independent Security QA Handoff (Owner: Vibhushan `@Vibhushan-2003`, Issue #18)
**Milestone Acceptance Items:** `L06`, `L07`, `L08`  
**Current Status:** PLATFORM SELF-VERIFIED (36 PASS, 0 FAIL, 4 BLOCKED); INDEPENDENT QA PENDING  
**Official Handoff Comment:** [Issue #18 Comment](https://github.com/Suthankan1/swiftdrop/issues/18#issuecomment-6312165950) posted on 2026-10-21.

#### Executable Security Verification Checklist for Vibhushan:
1. Ensure full stack is running (`check-readiness.sh` reports 13/13 PASS).
2. Execute the automated security test harness:
   ```bash
   ./tests/security-e2e/run-security-e2e.sh
   ```
3. Inspect and verify the 40 test scenarios:
   - **L06 (Edge & Resource Server JWT Validation - 27 tests):**
     - Verify HTTP 401 RFC 7807 ProblemDetail on: missing token, malformed token, unsigned `alg=none`, untrusted RSA signature, wrong issuer, wrong audience, expired token, future `nbf`, missing subject.
     - Verify edge header stripping (`X-User-Id`, `X-Roles`, `X-Keycloak-Sub`).
     - Verify strict CORS (permits `http://localhost:5173`, rejects unauthorized origins).
   - **L07 (Role Authorization Denial - 5 tests):**
     - Verify HTTP 403 Forbidden ProblemDetail on unapproved roles.
     - Verify public registration strictly confines privileges to `CUSTOMER` (zero self-granted staff roles).
     - Confirm 2 blocked downstream routes fail-closed via Gateway default-deny (404/401):
       - `L07-ROLE-04`: `/api/v1/admin/users` (blocked pending Issue #14).
       - `L07-ROLE-05`: `/api/v1/courier/deliveries` (blocked pending Issue #15).
   - **L08 (Resource-Owner & Tenant Isolation - 8 tests):**
     - Verify Customer A and Customer B receive strictly distinct, isolated profiles.
     - Verify query parameter tampering (`?id=...`) is ignored by Accounts and stripped by Gateway.
     - Confirm 2 blocked downstream routes fail-closed (404/401):
       - `L08-OWN-07`: `/api/v1/users/{id}` (blocked pending direct IDOR provider).
       - `L08-OWN-08`: `/api/v1/merchants/orders` (blocked pending Issue #8).
4. Record actual test run evidence in GitHub Issue #18.
5. **Rule:** Keep the 4 blocked downstream test cases honestly classified as BLOCKED. Transition `L06`, `L07`, and `L08` in [`LOGIN_ACCEPTANCE.md`](LOGIN_ACCEPTANCE.md) to "Independent QA Verified" only after Vibhushan's real test run.

---

## 6. Five-Role UI Route Status & Handoff (L05)

### 6.1 Current Frontend Implementation State

```text
frontend/src/app/router/routes.tsx
├── / (LandingPage) ............................ Implemented
├── /login (LoginPage) ......................... Implemented (OIDC PKCE + Google)
├── /auth/callback (CallbackPage) .............. Implemented
├── /logout (LogoutPage) ....................... Implemented (RP-initiated logout)
├── /customer (CustomerPage) ................... Implemented (Guarded: role="CUSTOMER")
├── /merchant (MerchantPage) ................... Implemented (Guarded: role="MERCHANT")
├── /courier (CourierPage) ..................... PENDING (Issue #15: Abhinaya & Vibhushan)
├── /dispatcher (DispatcherPage) ............... PENDING (Issue #16: Pushmitha & HarithKeshan)
└── /admin (AdminPage) ......................... PENDING (Issue #16: Pushmitha & HarithKeshan)
```

### 6.2 Security & Navigation Invariants
1. **Route Guarding:** `ProtectedRoute` checks `auth.snapshot.roles.includes(role)`. If unauthenticated, redirects to `/login`. If the user lacks the role, displays an access denied notice (`Access unavailable for your current role`).
2. **UX Boundary Only:** Client-side route guards are for navigation UX only. Every backend microservice enforces independent OAuth2 Resource Server JWT validation and RBAC.
3. **No Duplicate OIDC Code:** Role teams must **never** create separate Keycloak login clients or duplicate auth providers. All role views consume the shared `useAuth()` hook.
4. **No Staff Self-Elevation:** Keycloak public registration assigns solely the `CUSTOMER` role. Courier, Dispatcher, and Admin accounts must be provisioned directly by an administrator.

### 6.3 Handoff Blueprint & Tasks for Role Teams
Each functional team creates a small feature branch from `test` to introduce their role entry page without touching core auth infrastructure.

#### Implementation Pattern:
1. **Component Location:**
   - Merchant: `frontend/src/features/merchant/pages/MerchantPage.tsx`
   - Courier: `frontend/src/features/courier/pages/CourierPage.tsx`
   - Dispatcher: `frontend/src/features/dispatcher/pages/DispatcherPage.tsx`
   - Admin: `frontend/src/features/admin/pages/AdminPage.tsx`

2. **Route Registration (`frontend/src/app/router/routes.tsx`):**
   ```tsx
   <Route element={<ProtectedRoute role="MERCHANT" />}><Route path="merchant" element={<MerchantPage />} /></Route>
   <Route element={<ProtectedRoute role="COURIER" />}><Route path="courier" element={<CourierPage />} /></Route>
   <Route element={<ProtectedRoute role="DISPATCHER" />}><Route path="dispatcher" element={<DispatcherPage />} /></Route>
   <Route element={<ProtectedRoute role="ADMIN" />}><Route path="admin" element={<AdminPage />} /></Route>
   ```

3. **Navigation Integration (`frontend/src/auth/AuthPages.tsx`):**
   In `LoginPage`, display links conditionally based on role:
   ```tsx
   {auth.snapshot.roles.includes('MERCHANT') && <Link to="/merchant">Merchant space</Link>}
   {auth.snapshot.roles.includes('COURIER') && <Link to="/courier">Courier space</Link>}
   {auth.snapshot.roles.includes('DISPATCHER') && <Link to="/dispatcher">Dispatcher space</Link>}
   {auth.snapshot.roles.includes('ADMIN') && <Link to="/admin">Admin space</Link>}
   ```

4. **Component Template Reusing Shared UI:**
   ```tsx
   import { useAuth } from '../../../auth/context'
   export function MerchantPage() {
     const { snapshot, logout } = useAuth()
     return (
       <div className="page-container">
         <h1>Merchant Portal</h1>
         <p>Signed in as {snapshot.status === 'authenticated' ? snapshot.subject : ''}</p>
         <button onClick={() => { void logout() }}>Sign out</button>
       </div>
     )
   }
   ```

5. **Testing Pattern:**
   Use `renderWithAuth(<MerchantPage />)` from `frontend/src/test/authFixture.tsx` to test unauthenticated redirection, role denial, and authenticated rendering.

---

## 7. November 1 Academic Report Readiness Matrix (Issue #19)

**Academic Report Lead:** HarithKeshan (`@Harithkeshan`, Issue #19)  
**Architecture & Platform Sign-Off:** Suthankan1 ([`docs/team/PLATFORM_SIGNOFF_NOV01.md`](../team/PLATFORM_SIGNOFF_NOV01.md))  
**Official Handoff Comment:** [Issue #19 Comment](https://github.com/Suthankan1/swiftdrop/issues/19#issuecomment-6312174255) posted on 2026-10-21.

### 7.1 Report Section Readiness

| Report Section | Source Document / Reference | Responsible Author(s) | Review Status | Notes for HarithKeshan |
|---|---|---|---|---|
| **1. Executive Summary & Scope** | `docs/milestones/NOV_01.md` | HarithKeshan | Ready for drafting | Emphasize report + initial working login scope |
| **2. Role Group Use Cases** | Figures 01, 02, 03, 04 | Functional Pairs | Diagrams Rebranded | Customer (01), Merchant (02), Courier (03), Dispatcher (04) |
| **3. System Context** | Figure 05 | Suthankan1 + Team | Diagram Rebranded | Depicts SwiftDrop boundary & external actors |
| **4. Logical Container Architecture** | Figure 06, `BASELINE.md` | Suthankan1 (Platform) | **Signed Off** | Clear separation: Implemented subset vs Target baseline |
| **5. Protocol Justification** | `PLATFORM_SIGNOFF_NOV01.md` §3.2 | Suthankan1 (Platform) | **Signed Off** | Complete matrix for OIDC, REST, GraphQL, gRPC, Kafka, SSE |
| **6. Activity & Workflow Diagrams** | Figures 07, 08, 09 | Customer / Dispatcher / Courier | Diagrams Rebranded | Quote/Booking (07), Assignment (08), Events (09) |
| **7. Sequence Diagrams** | Figures 10, 11, 12, 13 | Functional Pairs + Platform | Diagrams Rebranded | Customer (10), Merchant (11), Courier (12), Google OIDC (13) |
| **8. Logical ERDs** | Figures 14, 15, 16 | Service Owners | Diagrams Rebranded | Delivery (14), Accounts/Pricing (15), Communications (16) |
| **9. Parcel Lifecycle State Machine** | Figure 17 | Courier + Dispatcher | Diagram Rebranded | 8 lifecycle states; Delivery owns canonical transitions |
| **10. Reliability Architecture** | `BASELINE.md` §Reliability | Suthankan1 + Team | Ready for drafting | Transactional outbox, Kafka consumer groups, SSE reconnect |
| **11. Security Design & Threat Model** | `PLATFORM_SIGNOFF_NOV01.md` §2.2 | Suthankan1 (Platform) | **Signed Off** | PKCE, in-memory tokens, dual-layer JWT, fail-closed RBAC |
| **12. Email OTP Evaluation (ADR-0009)** | `docs/architecture/adr/ADR-0009.md` | Suthankan1 (Platform) | **Proposed** | Keep native links + app TOTP; do not invent email OTP |
| **13. Team Contribution Matrix** | `CONTRIBUTION_LOG.csv`, PR records | HarithKeshan | Live Evidence | Real PR numbers, commit SHAs, transparent accounting |
| **14. Demonstration Evidence** | `LOGIN_ACCEPTANCE.md` | Suthankan1 + Eric + Vibhushan | Active Evidence | Real terminal traces, readiness checks, Google OIDC pass |

### 7.2 Clear Scope Boundary: Implemented vs. Planned
The report must maintain absolute academic honesty regarding what is implemented versus what is planned:
- **IMPLEMENTED (Demonstrable Code):**
  - React 19 + TypeScript + Vite SPA shell with in-memory token state.
  - Keycloak 26.8.1 with canonical `swiftdrop` realm, S256 PKCE, SwiftDrop theme, email verification, TOTP MFA, and Google broker.
  - Spring Cloud Gateway edge routing, CORS policy, header sanitization, and edge JWT validation.
  - Accounts & Partners Service (Java 21, Spring Boot 4.1.1) OAuth2 Resource Server, PostgreSQL 16 persistence, Flyway migrations, JIT customer profiles, and profile isolation.
  - Automated security test harness (36 passed, 0 failed, 4 blocked downstream).
- **PLANNED (Target Architecture & Diagrams Only):**
  - Delivery Operations Service, Pricing & Zones Service, Communications Service.
  - Read-only GraphQL Query API.
  - Internal synchronous gRPC/Protobuf communication.
  - Kafka KRaft event delivery and Transactional Outbox pattern.
  - Server-Sent Events (SSE) tracking stream.
  - Kubernetes / Helm deployment manifests (bonus scope).

### 7.3 Outstanding Academic Clarifications
1. **Lecturer Group-Size Exception:** Formal confirmation of the 9-member team size against the printed 10-member assignment specification (Status: **OPEN**).
2. **Submission Format & Destination:** Confirmation of report delivery mechanism (LMS PDF, GitHub repository link, printed document) (Status: **OPEN**).

---

## 8. Live Demonstration Plan (November 1 Checkpoint)

A reproducible, 11-step demonstration script for team rehearsals and final presentation:

| Step | Demonstration Action | Target URL / Command | Expected Observable Behavior |
|---|---|---|---|
| **A** | Fresh Environment Startup | `./platform/local/start-local.sh` | Starts Postgres (:5438), Keycloak (:8090), Mailpit (:8025). |
| **B** | Full-Stack Readiness Check | `./platform/local/check-readiness.sh` | Displays 13 Passed, 0 Warnings, 0 Failures. All services UP. |
| **C** | Customer Self-Registration | Browser: `http://localhost:5173/login` -> "Create account" | Fills registration form -> Keycloak prompts email verification. |
| **D** | Native Email Verification | Browser: `http://localhost:8025` (Mailpit) | Verification email received; user clicks link to activate. |
| **E** | Customer Login & Profile Load | Browser: `http://localhost:5173/login` | Redirects to `/customer`; displays full name, email, role `CUSTOMER`, Accounts UUID. |
| **F** | Brokered Google Login | Browser: Click "Continue with Google" | Authenticates via Google broker -> Displays Customer Space; role strictly `CUSTOMER`. |
| **G** | Authenticator-App TOTP MFA | Browser / Keycloak TOTP flow | Scans QR code with authenticator app; completes 6-digit challenge. |
| **H** | Clean Logout & Session Teardown | Click "Sign out" on Customer page | Tokens wiped from memory; redirected to `/`; Keycloak sessions zero. |
| **I** | Edge & Backend 401 JWT Denial | `curl -i http://localhost:8080/api/v1/users/me` | Edge returns HTTP 401 RFC 7807 ProblemDetail. |
| **J** | Resource-Owner Profile Isolation | Terminal / Automated tests | Customer A cannot view Customer B; spoofed `X-User-Id` headers stripped. |
| **K** | Role Navigation & Protection | Browser: Navigate to `/customer` while logged out | ProtectedRoute denies entry; redirects to `/login`. |

---

## 9. Milestone Schedule & GO / NO-GO Criteria

### 9.1 Recommended Schedule Leading to November 1

| Date | Target Deliverable / Milestone Activity | Owner(s) |
|---|---|---|
| **Oct 21** | Phase 4A completed: Tracker published, onboarding instructions ready. | Suthankan1 |
| **Oct 22** | Teammate repo onboarding: Invitations accepted; local setups verified. | All 9 members |
| **Oct 24** | Independent QA completed: Eric verifies L10; Vibhushan executes L06/L07/L08. | Eric, Vibhushan |
| **Oct 26** | Role UI landing PRs opened: Merchant, Courier, Dispatcher/Admin landing pages. | Role Pairs |
| **Oct 28** | Final report draft assembled: Incorporates Platform sign-off and diagrams. | HarithKeshan |
| **Oct 30** | Full live dry-run rehearsal: Live demo sequence executed end-to-end. | All members |
| **Nov 01** | Final Milestone Submission: Report delivered; repository tagged. | HarithKeshan & Suthankan1 |

### 9.2 GO / NO-GO Decision Matrix

| Gate | Decision Criterion | Current Status | Required for GO |
|---|---|---|---|
| **Gate 1** | Local login vertical slice demonstrably working | **PASSED** (Suthankan1) | Verified live stack on 5173, 8080, 8081, 8090 |
| **Gate 2** | Independent clean-clone reproduction (L10) | **PASSED** (Eric #17) | Full clean-clone stack reproduced, 13/13 preflight, 4/4 demo, 36 PASS/4 BLOCKED security E2E, browser verified |
| **Gate 3** | Independent security verification (L06, L07, L08) | **PASSED** (Vibhushan #18) | Vibhushan executed and signed off (PR #77) |
| **Gate 4** | All 20 Draw.io diagrams reviewed by assigned pairs | **PENDING** (All members) | Pair review recorded in report |
| **Gate 5** | Academic report compiled with honest scope separation | **PENDING** (HarithKeshan #19) | Complete draft with zero fabricated progress |
| **Gate 6** | Lecturer group size & submission format clarified | **PENDING** (All members) | Official lecturer response confirmed |
