# SwiftDrop Phase 2B Technical Identity Cutover Runbook

**Status: PREPARED — PENDING TEAM SIGNOFF AND SCHEDULED MAINTENANCE WINDOW**  
**Classification: OPERATIONAL RELEASE RUNBOOK — DO NOT EXECUTE OUTSIDE MAINTENANCE WINDOW**  
**Date:** 2026-10-20  
**Author / Platform Lead:** Suthankan1 (Platform & Security)  
**Related Documents:**  
- [Phase 2 Migration Proposal](PHASE_2_MIGRATION.md)  
- [Phase 2A Rehearsal Report](PHASE_2A_REHEARSAL.md)  
- [Login Integration Contract](../contracts/LOGIN_CONTRACT.md)  
- [Login Acceptance Matrix](../milestones/LOGIN_ACCEPTANCE.md)  

---

## 1. Preflight & Baseline Invariants

### 1.1 Scope Boundaries
This runbook defines the exact, sequential, non-destructive procedure for transitioning the active SwiftDrop technical identity contract:

| Binding | Active Legacy Environment | Target SwiftDrop Environment |
|---|---|---|
| **Keycloak Realm Name** | `devarom` | `swiftdrop` |
| **Public Browser Client ID** | `devarom-frontend` | `swiftdrop-frontend` |
| **JWT Audience Claim** | `devarom-api` | `swiftdrop-api` |
| **Local OIDC Issuer URI** | `http://localhost:8090/realms/devarom` | `http://localhost:8090/realms/swiftdrop` |
| **Google Broker Callback** | `http://localhost:8090/realms/devarom/broker/google/endpoint` | `http://localhost:8090/realms/swiftdrop/broker/google/endpoint` |

### 1.2 Non-Negotiable Invariants
- **Preserve Realm Internal ID:** The internal ID (`"id": "devarom"`) must NOT be recreated or renamed.
- **Preserve User UUIDs (`sub`):** Every registered user's internal Keycloak UUID must remain identical.
- **Preserve Accounts Profiles:** `account_profiles` table in `accounts_db` is keyed by `keycloak_sub`. Zero database updates or migrations are permitted.
- **Preserve Client Internal UUID:** The frontend client internal UUID (`2d80857e-52df-4d3c-9413-bc74a53e1de5`) must be retained.
- **Preserve External Federation:** Google broker alias (`google`) and external user ID links must remain intact.
- **Preserve Credentials & MFA:** Password hashes, RFC 6238 TOTP authenticators, and email verification states must survive.
- **Preserve Privilege Separation:** Self-registration is confined strictly to role `CUSTOMER`.
- **Zero Dual-Issuer Tolerance:** Each service deployment enforces exactly one approved issuer and one approved audience.

---

## 2. Human Approval Register & Prerequisites

Execution is strictly forbidden until every signoff below is recorded with timestamp and signature:

| Role / Stakeholder | Named Lead | Scope of Responsibility | Signoff State | Signature / Date |
|---|---|---|---|---|
| **Platform & Security Lead** | **Suthankan1** | Cutover orchestration, Keycloak rename, Gateway edge security | PENDING | *Pending window* |
| **Accounts Business Steward** | **Sinthuha** | `account_profiles` continuity, PostgreSQL trigger validation | PENDING | *Pending window* |
| **Frontend Steward** | **Gajanthan** | React SPA session adapter, callback & logout navigation | PENDING | *Pending window* |
| **Independent Security QA** | **Vibhushan** | Pre-cutover audit, negative JWT & cross-tenant denial review | PENDING | *Pending window* |
| **Google Cloud Administrator** | **Authorized Admin** | Google Cloud Console OAuth Client URI configuration | PENDING | *Pending window* |

### Required Maintenance Parameters:
- **Agreed Maintenance Window:** 60 minutes (Suggested: Off-peak, e.g., 22:00–23:00 UTC)
- **Backup Custody Owner:** Suthankan1 (Restricted local storage, mode 0700)
- **Expected Client Impact:** Active browser tokens (issuer `devarom`) will be invalidated; silent re-authentication or `/login` redirect will occur cleanly.
- **Rollback Decision Deadline:** T+30 minutes from start of maintenance window.

---

## 3. Storage Architecture & Consistent Backup Procedure

> [!CAUTION]
> **CRITICAL ARCHITECTURAL FINDING:**  
> The active Keycloak container (`devarom-keycloak`) runs with embedded H2 storage at `/opt/keycloak/data/h2/keycloakdb.mv.db`.  
> This file resides in the **container writable layer** (`overlayfs`) with **zero persistent volume mounts**.  
> Executing `docker compose down -v`, `docker rm -f devarom-keycloak`, or `docker system prune` will **PERMANENTLY DESTROY ALL REALM IDENTITIES**.

### 3.1 Why Hot Copying is Forbidden
H2's MVStore engine buffers modified pages in memory and maintains an active write lock. Copying `/opt/keycloak/data/h2` while the container is actively writing can produce a corrupt, torn, or unrecoverable database snapshot. A **consistent cold backup** with a stopped container is mandatory.

### 3.2 Cold Backup Execution Steps (Inside Maintenance Window)

```bash
# 1. Announce write freeze (no new registrations or logins)
# 2. Stop Keycloak container cleanly (allows H2 to flush write buffer and release file locks)
docker stop devarom-keycloak -t 30

# 3. Create restricted private backup directory (outside Git)
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
BACKUP_DIR=".local/backups/identity/${TIMESTAMP}"
mkdir -p "${BACKUP_DIR}"
chmod 700 "${BACKUP_DIR}"

# 4. Copy H2 database files from stopped container
docker cp devarom-keycloak:/opt/keycloak/data/h2 "${BACKUP_DIR}/"
chmod 600 "${BACKUP_DIR}"/h2/*

# 5. Compute SHA-256 integrity checksums
shasum -a 256 "${BACKUP_DIR}/h2/keycloakdb.mv.db" > "${BACKUP_DIR}/h2/keycloakdb.mv.db.sha256"

# 6. Verify backup checksum immediately
shasum -a 256 -c "${BACKUP_DIR}/h2/keycloakdb.mv.db.sha256"

# 7. Take consistent Accounts PostgreSQL backup
docker exec devarom-accounts-postgres pg_dump -U postgres -d accounts_db -F c -f "/tmp/accounts_backup_${TIMESTAMP}.dump"
docker cp devarom-accounts-postgres:"/tmp/accounts_backup_${TIMESTAMP}.dump" "${BACKUP_DIR}/"
docker exec devarom-accounts-postgres rm "/tmp/accounts_backup_${TIMESTAMP}.dump"
shasum -a 256 "${BACKUP_DIR}/accounts_backup_${TIMESTAMP}.dump" > "${BACKUP_DIR}/accounts_backup_${TIMESTAMP}.dump.sha256"
```

---

## 4. Current Identifier Snapshot & Inventory

Before applying changes, record the current entity inventory:

```bash
python3 platform/identity/cutover-identity-migration.py --preflight \
  --keycloak-url "http://localhost:8090" \
  --admin-user "admin" \
  --admin-pass "${KEYCLOAK_ADMIN_PASSWORD}"
```

### Pre-Cutover Verification Checklist:
- [ ] Realm internal ID: `devarom`
- [ ] Realm name: `devarom`
- [ ] Frontend client ID: `devarom-frontend`
- [ ] Frontend client UUID: `2d80857e-52df-4d3c-9413-bc74a53e1de5`
- [ ] Audience mapper ID: `53ec84cc-aa93-4344-84ce-369bbee64f6e`
- [ ] Current audience claim: `devarom-api`
- [ ] Total registered users counted and recorded
- [ ] Google IdP provider alias: `google`

---

## 5. Google Cloud Console Prerequisite Gate

> [!IMPORTANT]
> The target Google OAuth callback URL must be registered in Google Cloud Console **before** cutting over Keycloak.

### Administrator Actions:
1. Open [Google Cloud Console Credentials](https://console.cloud.google.com/apis/credentials).
2. Select Web Client ID (`999856063696-ae6aineb7tmng7pcje9me3902h547h0g.apps.googleusercontent.com`).
3. Under **Authorized redirect URIs**, add:
   `http://localhost:8090/realms/swiftdrop/broker/google/endpoint`
4. **DO NOT DELETE** the legacy URI (`http://localhost:8090/realms/devarom/broker/google/endpoint`) yet! Keep both during the migration and rollback window.
5. Click **Save**.
6. Verify Google accepts the new callback URI without redirect mismatch.

---

## 6. Maintenance Window & Write Freeze

1. Announce start of maintenance window to the team.
2. Direct all traffic away from the React SPA (or display maintenance banner).
3. Confirm with Sinthuha that no manual batch writes to `accounts_db` are active.
4. Restart `devarom-keycloak` after the cold backup:
   ```bash
   docker start devarom-keycloak
   # Wait for readiness on port 8090
   ./platform/local/check-readiness.sh
   ```

---

## 7. In-Place Keycloak Entity Migration

Execute the automated cutover tool with explicit authorization:

```bash
python3 platform/identity/cutover-identity-migration.py \
  --keycloak-url "http://localhost:8090" \
  --admin-user "admin" \
  --admin-pass "${KEYCLOAK_ADMIN_PASSWORD}" \
  --execute-cutover \
  --confirm-cutover-approval "SWIFTDROP-MAINTENANCE-APPROVED"
```

### What This Operation Executes Under the Hood:
1. `PUT /admin/realms/devarom`:
   ```json
   { "id": "devarom", "realm": "swiftdrop" }
   ```
2. `PUT /admin/realms/swiftdrop/clients/2d80857e-52df-4d3c-9413-bc74a53e1de5`:
   ```json
   { "clientId": "swiftdrop-frontend" }
   ```
3. `PUT /admin/realms/swiftdrop/clients/2d80857e-52df-4d3c-9413-bc74a53e1de5/protocol-mappers/models/53ec84cc-aa93-4344-84ce-369bbee64f6e`:
   ```json
   {
     "id": "53ec84cc-aa93-4344-84ce-369bbee64f6e",
     "name": "devarom-api-audience",
     "protocol": "openid-connect",
     "protocolMapper": "oidc-audience-mapper",
     "config": {
       "included.custom.audience": "swiftdrop-api"
     }
   }
   ```

### Immediate Health Check:
```bash
curl -s http://localhost:8090/realms/swiftdrop/.well-known/openid-configuration | jq .issuer
# MUST return: "http://localhost:8090/realms/swiftdrop"
```

---

## 8. Service & Frontend Configuration Deployment

Deploy the target environment configuration across Gateway, Accounts, and Frontend as a single atomic release:

### 8.1 API Gateway Configuration
Set environment variables on Spring Cloud Gateway:
```bash
export KEYCLOAK_ISSUER_URI="http://localhost:8090/realms/swiftdrop"
export KEYCLOAK_JWKS_URI="http://localhost:8090/realms/swiftdrop/protocol/openid-connect/certs"
export JWT_AUDIENCE="swiftdrop-api"
# Restart or reload Gateway
```

### 8.2 Accounts Service Configuration
Set environment variables on Accounts & Partners Service:
```bash
export KEYCLOAK_ISSUER_URI="http://localhost:8090/realms/swiftdrop"
export KEYCLOAK_JWKS_URI="http://localhost:8090/realms/swiftdrop/protocol/openid-connect/certs"
export JWT_AUDIENCE="swiftdrop-api"
# Restart or reload Accounts Service
```

### 8.3 React Frontend Configuration
Set environment variables on Vite SPA:
```bash
export VITE_KEYCLOAK_URL="http://localhost:8090"
export VITE_KEYCLOAK_REALM="swiftdrop"
export VITE_KEYCLOAK_CLIENT_ID="swiftdrop-frontend"
export VITE_AUTH_REDIRECT_URI="http://localhost:5173/auth/callback"
export VITE_AUTH_POST_LOGOUT_REDIRECT_URI="http://localhost:5173/"
# Rebuild frontend or restart Vite dev server
```

---

## 9. Post-Cutover JWT Validation Checks

Verify exact token validation behavior:

| Check | Action / Probe | Expected Outcome |
|---|---|---|
| **Target Positive Control** | Acquire JWT from `/realms/swiftdrop` (`aud=swiftdrop-api`) -> `GET /api/v1/users/me` | HTTP 200 OK + User profile |
| **Legacy Issuer Rejection** | Submit JWT with `iss=http://localhost:8090/realms/devarom` | HTTP 401 Unauthorized ProblemDetail |
| **Legacy Audience Rejection** | Submit JWT with `aud=devarom-api` | HTTP 401 Unauthorized ProblemDetail |
| **Untrusted Signature** | Submit JWT signed by foreign RSA key | HTTP 401 Unauthorized ProblemDetail |
| **Expired Token** | Submit expired JWT | HTTP 401 Unauthorized ProblemDetail |

---

## 10. Existing User & Profile Continuity Verification

1. Authenticate as an existing user who registered prior to cutover.
2. Call `GET http://localhost:8080/api/v1/users/me` via Gateway.
3. Verify that:
   - `keycloakSub` matches the user's pre-cutover UUID.
   - `id` matches the user's pre-cutover `account_profiles.id`.
   - `email` and `fullName` are unchanged.
   - Zero new rows were added to `account_profiles` table:
     ```sql
     SELECT COUNT(*) FROM account_profiles;
     ```

---

## 11. Native Email Verification & TOTP MFA Verification

1. **Email Verification:**
   - Register a new test user (`test-verify@swiftdrop.test`).
   - Confirm verification email arrives in Mailpit (`http://127.0.0.1:8025`).
   - Follow verification link and complete authentication.
   - Confirm user receives `CUSTOMER` role only.
2. **TOTP MFA:**
   - Log in with existing TOTP-enrolled user.
   - Confirm Keycloak presents authenticator-app challenge.
   - Submit valid 6-digit TOTP code; verify login succeeds.
   - Verify single-use replay rejection.

---

## 12. Interactive Google OAuth Login

1. Open clean browser profile to `http://localhost:5173/login`.
2. Click **Continue with Google**.
3. Verify redirection to Google OAuth consent screen with callback:
   `http://localhost:8090/realms/swiftdrop/broker/google/endpoint`
4. Authenticate using authorized Google Test Account.
5. Verify return to `http://localhost:5173/auth/callback` -> `/customer`.
6. Confirm profile loads from Accounts Service with matching Google identity.

---

## 13. Session Termination & Logout Verification

1. Click **Sign out** on the React SPA.
2. Confirm RP-initiated logout redirects to `http://localhost:5173/`.
3. Confirm in-memory tokens are completely purged.
4. Confirm Keycloak active SSO sessions for `swiftdrop-frontend` return to 0.
5. Attempt direct access to `/customer`; confirm immediate redirect to `/login`.

---

## 14. Security E2E Regression Run

Execute the automated Security E2E test suite against the live cutover stack:

```bash
KEYCLOAK_REALM="swiftdrop" \
KEYCLOAK_CLIENT_ID="swiftdrop-frontend" \
KEYCLOAK_ISSUER_URI="http://localhost:8090/realms/swiftdrop" \
JWT_AUDIENCE="swiftdrop-api" \
./tests/security-e2e/run-security-e2e.sh
```

**Required Acceptance Gate:**
- 36 PASS
- 0 FAIL
- 4 BLOCKED (unmapped staff/merchant backend provider endpoints)

---

## 15. Rollback Triggers & Conditions

Abort cutover and initiate immediate rollback if ANY of the following occur:
1. Keycloak Admin REST API rename fails or returns HTTP 5xx.
2. Any pre-existing user UUID (`sub`) changes after rename.
3. Accounts Service throws PostgreSQL constraint/trigger violations (`account_identity_immutable`).
4. Google OAuth callback returns `redirect_uri_mismatch` and cannot be resolved in 10 minutes.
5. Security E2E regression discovers any test failure.
6. Rollback decision deadline (T+30m) is reached without full verification.

---

## 16. Complete Rollback Procedure

### Step 1: Revert Keycloak in-place
```bash
python3 platform/identity/cutover-identity-migration.py \
  --keycloak-url "http://localhost:8090" \
  --admin-user "admin" \
  --admin-pass "${KEYCLOAK_ADMIN_PASSWORD}" \
  --rollback \
  --confirm-rollback-approval "SWIFTDROP-ROLLBACK-APPROVED"
```

### Step 2: Revert Services and Frontend Configuration
- Reset Gateway `KEYCLOAK_ISSUER_URI="http://localhost:8090/realms/devarom"`, `JWT_AUDIENCE="devarom-api"`.
- Reset Accounts `KEYCLOAK_ISSUER_URI="http://localhost:8090/realms/devarom"`, `JWT_AUDIENCE="devarom-api"`.
- Reset Frontend `VITE_KEYCLOAK_REALM="devarom"`, `VITE_KEYCLOAK_CLIENT_ID="devarom-frontend"`.

### Step 3: Emergency Database Restoration (If In-Place Reversion Fails)
If data corruption occurred:
1. `docker stop devarom-keycloak`
2. Restore H2 files from the cold backup directory:
   `docker cp ${BACKUP_DIR}/h2/. devarom-keycloak:/opt/keycloak/data/h2/`
3. Restore Accounts PostgreSQL:
   `docker exec -i devarom-accounts-postgres pg_restore -U postgres -d accounts_db --clean < ${BACKUP_DIR}/accounts_backup_*.dump`
4. `docker start devarom-keycloak`
5. Verify health via `./platform/local/check-readiness.sh`.

---

## 17. Final Evidence Register & Release Signoff

| Verification Item | Tested Baseline | Post-Cutover Observed Result | Steward Signoff |
|---|---|---|---|
| **OIDC Discovery** | `.../realms/devarom` | `.../realms/swiftdrop` (HTTP 200) | Suthankan1 |
| **User UUID Continuity** | 100% matched pre/post | Exact match across all users | Sinthuha |
| **Profile UUID Continuity** | `account_profiles.id` unchanged | Resolved same row; 0 duplicates | Sinthuha |
| **Email Verification** | Native Mailpit flow | PASS | Suthankan1 |
| **TOTP Challenge** | RFC 6238 challenge | PASS | Suthankan1 |
| **Google Sign-In** | Live interactive login | PASS | Gajanthan |
| **Security E2E** | 36 PASS, 0 FAIL, 4 BLOCKED | 36 PASS, 0 FAIL, 4 BLOCKED | Vibhushan |
| **Zero Secrets Logged** | Audited git & logs | Verified clean | Suthankan1 |

---

**END OF RUNBOOK — DO NOT EXECUTE ACTIVE CUTOVER WITHOUT EXPLICIT TEAM APPROVAL**
