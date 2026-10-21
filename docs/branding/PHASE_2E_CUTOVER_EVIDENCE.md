# SwiftDrop Phase 2E Local Development Identity Cutover Evidence Report

**Status: COMPLETED & VERIFIED (SUCCESS)**  
**Execution Date:** 2026-10-21  
**Lead / Operator:** Suthankan1 (Platform & Security)  
**Verification Base:** Commit `c26ca433083c1d7f56ca733df90368daf8df838c` (PR #63 merged on `origin/test`)  
**Target Environment:** Local SwiftDrop Development Services  

---

## 1. Executive Summary

During the authorized maintenance window on 2026-10-21, the live local development identity cutover from legacy `devarom` to target `swiftdrop` was executed in-place without container recreation, without subject rewrite, and with zero data loss.

| Identity Parameter | Legacy Active Value | Cutover Verified Value | Verification Status |
|---|---|---|---|
| **Keycloak Realm Name** | `devarom` | `swiftdrop` | **PASS (HTTP 200 on new discovery; legacy returns 404)** |
| **Realm Internal ID** | `devarom` | `devarom` | **PASS (Preserved internal container identifier)** |
| **Frontend Public Client** | `devarom-frontend` | `swiftdrop-frontend` | **PASS (UUID `3e66328a-14ec-4d2c-a06d-b92175d7fc37` preserved)** |
| **JWT Audience Claim** | `devarom-api` | `swiftdrop-api` | **PASS (Mapper `731dc2c9-468d-4a9e-8936-c7bab76e3d0c` preserved)** |
| **OIDC Discovery Issuer** | `http://localhost:8090/realms/devarom` | `http://localhost:8090/realms/swiftdrop` | **PASS (RFC 8414 conformant, single-issuer enforced)** |
| **Accounts PostgreSQL DB** | 79 profile records | 79 profile records | **PASS (Zero mutations, 0 duplicate profiles, UUIDs intact)** |
| **Google Broker Callback** | Dual-registered URIs | Dual-registered URIs | **PASS (Interactive Google login verified end-to-end)** |

---

## 2. Pre-Cutover Verification & Safety Gates

Prior to live execution, all prerequisite safety gates were audited and verified:
1. **Repository Synchronization:** Working tree aligned with `origin/test` at verified merge commit `c26ca433083c1d7f56ca733df90368daf8df838c` (PR #63).
2. **Approved Maintenance Window:** Activated with local application traffic quiesced and no teammates active.
3. **Pre-Cutover Realm Audit:** Confirmed active live realm was `devarom` with zero partial migrations.
4. **Disaster Recovery Baseline & Cold Backups:**
   - Pre-cutover cold backup archive generated: `cold_backup_1792555388` in `platform/identity/backups/cold_backup_1792555388`.
   - Keycloak H2 Database SHA-256: `cd9c66b5015119cc6b810f16d9fbf837aa841df3d8af05cab3ba2cb28fd8f442`.
   - Accounts PostgreSQL Database Snapshot: `accounts_db_backup.sql` (SHA-256: `bb1694f382a3264ec4210d73f1d3c05988d5786e680a6b7d5ae3e7536df5e381`).
   - Isolated disaster restoration proof verified with ephemeral container `quay.io/keycloak/keycloak:26.8.1` (`RESTORE_VERIFIED_SUCCESS`).
5. **Google Callback Dual-Registration:** Confirmed both target (`/realms/swiftdrop/broker/google/endpoint`) and fallback legacy (`/realms/devarom/broker/google/endpoint`) redirect URIs registered.
6. **Pre-Migration Metadata Baseline:** Keycloak user UUIDs, roles (`CUSTOMER`, `MERCHANT`, `COURIER`, `DISPATCHER`, `ADMIN`), federated Google links, and credential metadata cataloged.

---

## 3. In-Place Migration Execution

Execution was carried out using the reviewed cutover migration utility:
```bash
python3 platform/identity/cutover-identity-migration.py \
  --execute-cutover \
  --confirm-cutover-approval "SWIFTDROP-MAINTENANCE-APPROVED"
```

### Observed Execution Log:
- Target realm existence check: Confirmed `swiftdrop` did not exist.
- In-place realm rename: `devarom` -> `swiftdrop` (Internal ID `devarom` preserved).
- In-place public client rename: `devarom-frontend` -> `swiftdrop-frontend` (Client UUID `3e66328a-14ec-4d2c-a06d-b92175d7fc37` preserved).
- Audience protocol mapper update: `included.custom.audience` updated to `swiftdrop-api` (Mapper UUID `731dc2c9-468d-4a9e-8936-c7bab76e3d0c` preserved).
- Redirect URIs and Web Origins: Updated to `http://localhost:5173/auth/callback` and `http://localhost:5173`.
- User and Credential Invariants: 100% of user subjects preserved (`0859aea1-1534-4a98-b7d7-594b14b36553`), email verification `True`, federated broker `['google']`, default roles `['default-roles-devarom']`.

---

## 4. Application Runtime Configuration Alignment

Local application processes were restarted with unified SwiftDrop environment bindings:
- **Spring Cloud Gateway (port 8080):**
  - `KEYCLOAK_ISSUER_URI="http://localhost:8090/realms/swiftdrop"`
  - `KEYCLOAK_JWKS_URI="http://localhost:8090/realms/swiftdrop/protocol/openid-connect/certs"`
  - `JWT_AUDIENCE="swiftdrop-api"`
  - `ACCOUNTS_SERVICE_URL="http://localhost:8081"`
- **Accounts & Partners Service (port 8081):**
  - `KEYCLOAK_ISSUER_URI="http://localhost:8090/realms/swiftdrop"`
  - `KEYCLOAK_JWKS_URI="http://localhost:8090/realms/swiftdrop/protocol/openid-connect/certs"`
  - `JWT_AUDIENCE="swiftdrop-api"`
  - `ACCOUNTS_DB_URL="jdbc:postgresql://localhost:5438/accounts_db"`
- **React Frontend SPA (port 5173):**
  - `VITE_KEYCLOAK_URL="http://localhost:8090"`
  - `VITE_KEYCLOAK_REALM="swiftdrop"`
  - `VITE_KEYCLOAK_CLIENT_ID="swiftdrop-frontend"`
  - `VITE_API_GATEWAY_URL="http://localhost:8080"`
  - `VITE_AUTH_REDIRECT_URI="http://localhost:5173/auth/callback"`

---

## 5. Post-Cutover Verification Results

### 5.1 Protocol & Discovery Verification
- Target OIDC Discovery: `http://localhost:8090/realms/swiftdrop/.well-known/openid-configuration` returned HTTP 200 with issuer `http://localhost:8090/realms/swiftdrop`.
- Legacy Issuer Denial: `http://localhost:8090/realms/devarom/.well-known/openid-configuration` returned HTTP 404. Strict single-issuer enforcement active.
- JWKS Publishing: 3 valid RSA signing keys published on `http://localhost:8090/realms/swiftdrop/protocol/openid-connect/certs`.

### 5.2 User & Profile Continuity
- Keycloak User UUIDs: 100% match pre- and post-cutover (`0859aea1-1534-4a98-b7d7-594b14b36553`).
- Accounts PostgreSQL Table `account_profiles`: Exactly 79 rows pre-cutover and 79 rows post-cutover.
- Zero duplicate profiles created; profile UUID `c3b6daad-08d5-4514-9d3c-083b109778e1` remains perfectly linked to `0859aea1-1534-4a98-b7d7-594b14b36553`.
- Authenticated Gateway Call: `GET /api/v1/users/me` returned HTTP 200 with profile payload matching the token subject.

### 5.3 Interactive Google Login Verification
- Google Broker automated test (`google_broker_smoke.py`): 5 of 5 steps passed, broker redirect accepted by Google Auth with HTTP 302.
- **Interactive Browser Verification:** Performed and confirmed by operator in browser at `http://localhost:5173/login`:
  - Clicked "Continue with Google".
  - Navigated through Google authentication consent.
  - Successfully returned to `http://localhost:5173/auth/callback`.
  - Customer profile successfully loaded from Accounts Service via Gateway.
  - No claims errors, session mismatches, or console exceptions.

### 5.4 Test Suites & Security Regressions Summary

| Suite / Test Target | Command / Harness | Result | Details |
|---|---|---|---|
| **Security E2E Suite** | `tests/security-e2e/security_e2e_runner.py` | **36 PASS, 0 FAIL, 4 BLOCKED** | Old issuer/audience rejection, cross-tenant isolation, tamper checks verified. (4 blocked are unmapped provider endpoints). |
| **Login Demo Smoke** | `tests/security-e2e/demo_smoke.py` | **PASS (4/4 Scenarios)** | Scenario A (Reg/Email), Scenario B (Login/Profile), Scenario C (TOTP MFA), Scenario D (Negative Auth & Ownership). |
| **Keycloak TOTP Smoke** | `platform/identity/tests/totp_smoke.py` | **PASS (8/8 Suites)** | RFC 6238 compliance, invalid code rejection, replay prevention, voluntary enrollment. |
| **Email Verification Smoke** | `platform/identity/tests/email_verification_smoke.py` | **PASS (16/16 Checks)** | Action-token consumption, Mailpit delivery, replay rejection, negative bypasses. |
| **Identity Unit Pytest** | `pytest platform/identity/tests` | **PASS (80/80 Tests)** | Configuration parsers, backup-restore harnesses, rehearsal mocks. |
| **Frontend Vitest Suite** | `cd frontend && pnpm test --run` | **PASS (70/70 Tests in 8 files)** | Session storage, AuthProvider, protected routes, customer profile components. |
| **API Gateway Tests** | `cd services/api-gateway && ./mvnw test` | **PASS (13/13 Tests)** | Routing, JWT claims parsing, downstream fault tolerance. |
| **Accounts Service Tests** | `cd services/accounts-service && ./mvnw test` | **PASS (37/37 Tests)** | Resource server security, profile CRUD, ownership isolation, target contract tests. |
| **Security Orchestrator** | `./tests/security-e2e/run-security-e2e.sh` | **PASS (100% Clean Pass)** | Comprehensive end-to-end multi-phase orchestration passed. |

---

## 6. Backup & Rollback Status

- **Cold Backup Directory:** `platform/identity/backups/cold_backup_1792555388/` (secured with mode 0700 permissions).
- **Accounts DB Snapshot:** `platform/identity/backups/cold_backup_1792555388/accounts_db_backup.sql`.
- **Rollback Decision:** NOT INVOKED. All acceptance criteria and safety gates passed.
- **Rollback Capability:** Documented in Section 16 of `docs/branding/PHASE_2B_CUTOVER_RUNBOOK.md` should future emergency restoration be required.

---

## 7. Signoff and Handoff

- **Cutover Result:** **SUCCESS**
- **Outstanding Problems:** None. Zero critical blockers.
- **Next Small Task:** Open PR targeting `test` with updated test orchestrators and cutover evidence documentation for team review.
