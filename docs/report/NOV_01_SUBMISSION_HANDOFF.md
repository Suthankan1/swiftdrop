# SwiftDrop — November 1 Academic Submission & Login Demonstration Handoff

**Document Reference:** `docs/report/NOV_01_SUBMISSION_HANDOFF.md`  
**Target Milestone:** November 1, 2026 (Initial Login Vertical Slice & System Architecture Design)  
**Report Coordinator:** HarithKeshan ([@Harithkeshan](https://github.com/Harithkeshan)) — Issue #19  
**Platform Architect:** Suthankan1 ([@Suthankan1](https://github.com/Suthankan1))  
**Repository:** [https://github.com/Suthankan1/swiftdrop](https://github.com/Suthankan1/swiftdrop)  
**Target Git Branch:** `test`  
**Date of Assembly:** October 22, 2026  

---

## 1. Executive Handoff Summary

The SwiftDrop engineering team submits this comprehensive milestone package for the **November 1, 2026** academic evaluation checkpoint.

### Milestone Scope Boundaries
In strict compliance with the project charter ([`AGENTS.md`](file:///d:/PROJECTS/swiftdrop/AGENTS.md)) and milestone scope ([`docs/milestones/NOV_01.md`](file:///d:/PROJECTS/swiftdrop/docs/milestones/NOV_01.md)), the deliverables consist of:
1. **Fully Elaborated System Architecture (v1.3):** Complete enterprise architecture specifications across 28 academic report sections, covering 4 business microservices, 1 API Gateway, 1 read-only GraphQL Query API, Kafka event backbone, gRPC internal contracts, and SSE real-time tracking topology.
2. **20 Architectural Diagrams:** System context, logical architecture, use cases, activity workflows, sequence diagrams, database ERDs, parcel state machine, and deployment targets.
3. **Working & Verified Initial Login Vertical Slice:** An end-to-end, functional, and security-hardened authentication pipeline comprising:
   - React 19 + TypeScript + Vite SPA
   - Keycloak 26.8.1 Identity Provider (custom SwiftDrop login theme, Google OIDC brokering, Mailpit email verification, RFC 6238 TOTP MFA)
   - Spring Cloud API Gateway (edge routing, CORS, JWT extraction, role forwarding)
   - Accounts & Partners Microservice (Spring Boot 3.4.3, Java 21, Flyway V1–V3 migrations, Spring Security OAuth2 Resource Server, PostgreSQL profile isolation)
4. **Academically Honest Reporting:** Core logistics engines (Delivery Operations, Pricing & Zones, Communications, Kafka event streaming, and Kubernetes deployment) are explicitly documented as **PLANNED ARCHITECTURE** rather than claimed as implemented software.

---

## 2. Deliverables Checklist

| Deliverable | Repository Location | Format / Type | Verification Evidence |
|:---|:---|:---|:---|
| **Master Academic Report Draft** | [`docs/report/NOV_01_REPORT_DRAFT.md`](file:///d:/PROJECTS/swiftdrop/docs/report/NOV_01_REPORT_DRAFT.md) | GitHub Markdown (28 Sections) | Traces 100% of assignment criteria |
| **Review & Signoff Matrix** | [`docs/report/NOV_01_REVIEW_MATRIX.md`](file:///d:/PROJECTS/swiftdrop/docs/report/NOV_01_REVIEW_MATRIX.md) | Markdown Register | Peer review records & signoffs |
| **Diagram Package (20 Diagrams)** | [`docs/diagrams/`](file:///d:/PROJECTS/swiftdrop/docs/diagrams/README.md) | SVG previews + `.drawio` editable sources | Rebranded, audited, and referenced in report |
| **Nine-Member Contribution Log** | [`docs/team/CONTRIBUTION_LOG.csv`](file:///d:/PROJECTS/swiftdrop/docs/team/CONTRIBUTION_LOG.csv) | CSV Record | Reconciles PR #74–#86 commit evidence |
| **Login Contract Specification** | [`docs/contracts/LOGIN_CONTRACT.md`](file:///d:/PROJECTS/swiftdrop/docs/contracts/LOGIN_CONTRACT.md) | Architecture Contract | Formal endpoint, token, and header schemas |
| **Security E2E Test Suite** | `services/accounts-service/src/test/` | Automated Java/RestAssured/JUnit 5 | 36 PASS / 0 FAIL / 4 BLOCKED (PR #76) |
| **Evaluator Reproduction Script** | `scripts/clean-clone-setup.ps1` | PowerShell Automation Script | Independently reproduced by Eric (PR #85) |

---

## 3. Evaluator Clean-Clone Demonstration Protocol

This runbook provides an independent academic evaluator with step-by-step instructions to reproduce the live authentication vertical slice on a standard development machine.

### 3.1 Host Toolchain Requirements
- **Operating System:** Windows 10/11, macOS 13+, or Ubuntu 22.04 LTS
- **Docker Engine & Docker Compose:** Docker Desktop 4.28+ (minimum 4 GB allocated RAM)
- **Java Development Kit (JDK):** JDK 21 (Eclipse Temurin 21.0.6 or OpenJDK 21)
- **Node.js & npm:** Node.js v20 LTS (or v22) with npm 10+
- **Git:** Git 2.40+
- **Terminal:** PowerShell 7+ (Windows) or Bash / Zsh (Unix)

---

### 3.2 Step-by-Step Execution Sequence

#### Step 1: Clean Clone & Branch Checkout
Clone the repository and switch to the authoritative integration branch (`test`):
```bash
git clone https://github.com/Suthankan1/swiftdrop.git
cd swiftdrop
git checkout test
```

#### Step 2: Environment Configuration
Initialize local development environment variables from template:
```powershell
# Copy local environment template (contains safe local defaults; NO production secrets)
Copy-Item .env.example .env
```

#### Step 3: Launch Local Identity & Database Infrastructure
Start Keycloak 26.8.1, PostgreSQL 16, and Mailpit SMTP via Docker Compose:
```powershell
docker compose -f infrastructure/docker/docker-compose.yml up -d
```
*Healthcheck verification:*
- Keycloak Admin Console: `http://localhost:8080/auth` (or direct `:8080` depending on edge port)
- Mailpit Web UI: `http://localhost:8025`
- PostgreSQL: `localhost:5432` (database `accounts_db`)

Wait approximately 30–45 seconds until Keycloak finishes realm import (`realm-export.json`).

#### Step 4: Launch Accounts & Partners Service
Start the Spring Boot Resource Server:
```powershell
cd services/accounts-service
./mvnw spring-boot:run
```
*Expected log output:*
- Flyway executes migrations `V1__create_user_profiles.sql`, `V2__seed_test_profiles.sql`, `V3__create_staff_profiles.sql`.
- Tomcat starts on port `8081`.
- Spring Security registers JWT decoder targeting Keycloak JWKS: `http://localhost:8080/realms/swiftdrop/protocol/openid-connect/certs`.

#### Step 5: Launch Spring Cloud API Gateway
In a separate terminal:
```powershell
cd gateway
./mvnw spring-boot:run
```
*Expected log output:*
- Netty starts on port `8080`.
- Routes registered: `/api/v1/auth/**` -> Keycloak, `/api/v1/accounts/**` -> `http://localhost:8081`.

#### Step 6: Launch React SPA Frontend
In a separate terminal:
```powershell
cd frontend
npm install
npm run dev
```
*Expected log output:*
- Vite server ready at `http://localhost:5173`.

---

### 3.3 Verification Scenarios for Evaluators

#### Scenario 1: Standard Customer Registration & Email Verification (L01, L02)
1. Open browser at `http://localhost:5173`.
2. Click **Register as Customer**.
3. Fill registration form:
   - Username: `demo.customer`
   - Email: `demo.customer@example.com`
   - Password: `SecurePassword123!`
4. Keycloak presents **Verify Email** screen.
5. Open Mailpit UI at `http://localhost:8025`.
6. Open the newly received confirmation email and click the verification link.
7. Verification succeeds; user is directed to the SwiftDrop Customer Dashboard.

#### Scenario 2: Protected Profile Retrieval & Token Isolation (L04, L06, L08)
1. In the customer dashboard, navigate to **My Profile**.
2. Frontend issues request: `GET http://localhost:8080/api/v1/accounts/me` with `Authorization: Bearer <Keycloak_JWT>`.
3. Gateway validates JWT and routes to Accounts Service.
4. Accounts Service extracts `sub` claim, queries `user_profiles`, and returns profile payload:
   ```json
   {
     "id": "...",
     "keycloakId": "...",
     "email": "demo.customer@example.com",
     "role": "CUSTOMER",
     "fullName": "Demo Customer"
   }
   ```
5. *Security check:* Verify browser DevTools `localStorage` and `sessionStorage`. Confirm **NO tokens** are stored in browser persistence (stored strictly in-memory by React OIDC client).

#### Scenario 3: Real Google OIDC Identity Brokering (L03)
*Note:* Requires internet connectivity and registered OAuth Client ID in Keycloak.
1. At the login screen, click **Sign in with Google**.
2. Keycloak redirects to `https://accounts.google.com/o/oauth2/v2/auth`.
3. Authenticate with valid Google credentials.
4. Google returns authorization code to Keycloak broker endpoint.
5. Keycloak issues internal SwiftDrop JWT with user's Google email.
6. Accounts Service auto-provisions user profile; dashboard opens successfully.

#### Scenario 4: Authenticator-App TOTP Multi-Factor Authentication (L02)
1. Login as an enrolled test account (or configure TOTP via Keycloak Account Console).
2. Keycloak prompts for 6-digit authenticator code.
3. Enter current RFC 6238 code generated by Google Authenticator / 1Password / Bitwarden.
4. Sign-in completes successfully; invalid or expired code is immediately rejected.

#### Scenario 5: Role-Based Access Control & Negative Enforcement (L07)
1. While logged in as `CUSTOMER`:
2. Manually navigate browser to `http://localhost:5173/admin` or `http://localhost:5173/dispatcher`.
3. React router guard intercepts route and redirects to `/access-denied` (L05).
4. Direct API probe test using curl:
   ```bash
   curl -i -X GET http://localhost:8080/api/v1/accounts/staff \
     -H "Authorization: Bearer <CUSTOMER_JWT>"
   ```
   **Expected Response:** `HTTP/1.1 403 Forbidden` (RBAC enforced by Spring Security `@PreAuthorize`).

#### Scenario 6: Invalid / Expired / Forged JWT Rejection (L06)
1. Send request with arbitrary string token:
   ```bash
   curl -i -X GET http://localhost:8080/api/v1/accounts/me \
     -H "Authorization: Bearer forged.invalid.token"
   ```
   **Expected Response:** `HTTP/1.1 401 Unauthorized` with `WWW-Authenticate: Bearer error="invalid_token"`.

#### Scenario 7: Clean Session Teardown & Logout (L11)
1. Click **Log Out** in the dashboard navigation bar.
2. React client executes OIDC End Session flow against Keycloak (`/protocol/openid-connect/logout`).
3. In-memory access and refresh tokens are wiped from memory.
4. Browser redirects back to unauthenticated landing page.
5. Clicking browser "Back" button does not restore protected session state.

---

## 4. Milestone Acceptance Gate Matrix (L01–L12)

| Gate ID | Acceptance Requirement | Documented Evidence | Final Gate Status |
|:---:|:---|:---|:---:|
| **L01** | Keycloak Containerization & Realm Boot | PR #35 (commit `c993204`), Docker Compose configuration | **PASS** |
| **L02** | Local Registration, Email Verification (Mailpit) & TOTP | PR #48 (commit `b72c005`), PR #49 (commit `7ecfc33`) | **PASS** |
| **L03** | Google Identity Brokerage (Live Verified) | PR #54 (commit `d2f9b08`), PR #55 (commit `bf875a0`) | **PASS** |
| **L04** | Spring Cloud Gateway Edge Token Forwarding | PR #39 (commit `56341b4`) | **PASS** |
| **L05** | React 19 Frontend Shell & Role Guard Routing | PR #41, PR #42, PR #75, PR #80, PR #84 | **PASS** |
| **L06** | Accounts Service Stateless JWT Signature Verification | PR #45 (commit `c6b31bb`), PR #50 (commit `3d05454`) | **PASS** |
| **L07** | Role-Based Access Control (`CUSTOMER`, `COURIER`, `DISPATCHER`, `ADMIN`) | PR #45, PR #86, Security Suite (36 passing scenarios) | **PASS** (Core) / **4 BLOCKED** (Downstream Provider Endpoints) |
| **L08** | Multi-Tenant Resource Ownership & Profile Isolation | PR #45, PR #76 (36 passing scenarios) | **PASS** (Core) / **4 BLOCKED** (Downstream Provider Endpoints) |
| **L09** | Rebranded SwiftDrop Documentation & Draw.io Figures | PR #64, PR #74, PR #77, PR #79, PR #82, PR #83 | **PASS** |
| **L10** | Independent Clean-Clone Reproduction by Peer | PR #85 (commit `1c51a70`, Eric Praveen) | **PASS** |
| **L11** | Full-Stack End-to-End User Flow Execution | PR #53 (commit `882c65e`), PR #55, PR #76 | **PASS** |
| **L12** | Complete Reviewed Academic Report & Signoff Register | PR #87 (docs/report/NOV_01_REPORT_DRAFT.md, HarithKeshan) | **PASS** |

---

## 5. Human Review & Pre-Submission Action Checklist

Before submitting the milestone report to the university evaluation portal, the team must complete the following procedural actions:

- [x] All 28 report sections compiled in GitHub Markdown master.
- [x] All 20 Draw.io diagrams updated, exported as SVG, and verified accessible.
- [x] Nine-member contribution matrix verified against git commits and pull requests.
- [x] Independent clean-clone reproduction executed and signed off by Eric (PR #85).
- [x] Independent security test suite executed and signed off by Vibhushan (PR #76).
- [x] Staff profile read models and Flyway V3 migration implemented and merged (PR #86).
- [ ] Receive formal peer review approvals on Pull Request #87 (`docs/nov19-reviewed-report` -> `test`).
- [ ] Receive written lecturer clarification regarding 9-member team composition (L-01).
- [ ] Reconfirm university assignment dropbox format (Markdown link vs rendered PDF) (L-02).
- [ ] Project owner ([@Suthankan1](https://github.com/Suthankan1)) conducts final signoff and official milestone tag creation (`v0.1-nov01`).

---

## 6. Official Handoff Attestation

This handoff package has been assembled by **HarithKeshan** on behalf of the SwiftDrop engineering team.

We certify that:
1. Every claimed test result reflects actual automated or manual test runs.
2. Unimplemented business domains are transparently identified as architectural designs.
3. No credentials, live tokens, or private customer data are stored in this repository.
4. The deliverables satisfy the requirements of the November 1, 2026 academic milestone.

*Submitted for Review on October 22, 2026.*
