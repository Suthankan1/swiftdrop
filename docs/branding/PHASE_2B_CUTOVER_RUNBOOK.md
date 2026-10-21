# SwiftDrop Phase 2B Technical Identity Cutover Runbook

**Status: PREPARED & SAFETY HARDENED — PENDING TEAM SIGNOFF AND SCHEDULED MAINTENANCE WINDOW**  
**Classification: OPERATIONAL RELEASE RUNBOOK — DO NOT EXECUTE OUTSIDE MAINTENANCE WINDOW**  
**Lead / Author:** Suthankan1 (Platform & Security)  
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
- **Preserve Client Internal UUID:** The frontend client internal UUID (dynamically discovered from the live installation) must be retained.
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

## 3. Storage Architecture & Proven Disaster Recovery

> [!CAUTION]
> **CRITICAL ARCHITECTURAL FINDING:**  
> The active Keycloak container (`devarom-keycloak`) runs with embedded H2 storage at `/opt/keycloak/data/h2/keycloakdb.mv.db`.  
> This file resides in the **container writable layer** (`overlayfs`) with **zero persistent volume mounts**.  
> Executing `docker compose down -v`, `docker rm -f devarom-keycloak`, or `docker system prune` will **PERMANENTLY DESTROY ALL REALM IDENTITIES**.

### 3.1 Consistent Cold Backup (Inside Maintenance Window)
H2's MVStore buffers modified pages in memory and maintains an active write lock. Copying `/opt/keycloak/data/h2` while active risks creating a corrupt snapshot. A cold backup from a cleanly stopped container is mandatory.

The automated backup tool executes the consistent cold copy and immediately verifies SHA-256 integrity:

```bash
# Execute consistent cold backup during authorized maintenance window
python3 platform/identity/backup-restore-identity.py --backup-live-cold \
  --confirm-maintenance-stop "SWIFTDROP-MAINTENANCE-STOP-AUTHORIZED"

# Note: The tool stops the container (30s timeout), copies /opt/keycloak/data/h2 to
# .local/backups/identity/cold_backup_<timestamp> (mode 0700/0600), verifies SHA-256,
# and automatically restarts devarom-keycloak.

# Optional consistent Accounts PostgreSQL backup (if desired alongside Keycloak cold backup):
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
docker exec -e PGPASSWORD=accounts_dev_password devarom-accounts-postgres pg_dump -U accounts_app -d accounts_db -F c -f "/tmp/accounts_backup_${TIMESTAMP}.dump"
docker cp devarom-accounts-postgres:"/tmp/accounts_backup_${TIMESTAMP}.dump" "${BACKUP_DIR}/"
docker exec devarom-accounts-postgres rm "/tmp/accounts_backup_${TIMESTAMP}.dump"
shasum -a 256 "${BACKUP_DIR}/accounts_backup_${TIMESTAMP}.dump" > "${BACKUP_DIR}/accounts_backup_${TIMESTAMP}.dump.sha256"
```

### 3.2 Verify Backup Directory Integrity
```bash
BACKUP_DIR=$(ls -td .local/backups/identity/cold_backup_* | head -n 1)
python3 platform/identity/backup-restore-identity.py --verify-backup "${BACKUP_DIR}"
```

### 3.3 Test Bootable Isolated Restoration (Disaster Recovery Proof)
Before touching production identities, prove that the backup can be booted and read by an isolated Keycloak 26.8.1 container:

```bash
python3 platform/identity/backup-restore-identity.py \
  --test-restore-isolated "${BACKUP_DIR}" \
  --port 8098
```

**Verification Invariants Proven on Restored Container:**
- Realm internal ID matches `devarom`
- Frontend client internal UUID matches source
- Audience protocol mapper matches source
- 100% of user UUIDs (`sub`) are present
- Email verification states (`emailVerified`) are preserved
- Credentials (password, TOTP) are preserved
- Google federated identity links are preserved
- Structured evidence saved to `${BACKUP_DIR}/restoration_evidence.json`

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
- [ ] Frontend client UUID: dynamically discovered from live server (e.g. `3e66328a-14ec-4d2c-a06d-b92175d7fc37`)
- [ ] Audience mapper ID: dynamically discovered from live server (e.g. `731dc2c9-468d-4a9e-8936-c7bab76e3d0c`)
- [ ] Current audience claim: `devarom-api`
- [ ] Total registered users counted via pagination and recorded
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
4. **DO NOT DELETE** the legacy URI (`http://localhost:8090/realms/devarom/broker/google/endpoint`)! Keep both active throughout the migration and rollback window.
5. **DO NOT ROTATE** or change the OAuth client ID or client secret.
6. **DO NOT CREATE** a new OAuth client.
7. Click **Save**.
8. Set verification flag: `export GOOGLE_CALLBACK_VERIFIED=true`.

---

## 6. Safety Gate Evaluation (GO / NO-GO)

Run the comprehensive safety gate tool to verify that all machine-verifiable and human-confirmed prerequisites are satisfied:

```bash
export MAINTENANCE_WINDOW_APPROVED=true
export GOOGLE_CALLBACK_VERIFIED=true
python3 platform/identity/backup-restore-identity.py --safety-status
```

Only proceed to Step 7 if the tool returns:
`Live Cutover Classification : GO`

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
1. Verifies target realm `swiftdrop` does not already exist.
2. `PUT /admin/realms/devarom`:
   ```json
   { "id": "devarom", "realm": "swiftdrop" }
   ```
3. Dynamically queries frontend client internal UUID (`cid`) and executes:
   `PUT /admin/realms/swiftdrop/clients/{cid}`:
   ```json
   { "clientId": "swiftdrop-frontend" }
   ```
4. Dynamically queries audience mapper ID (`mid`) and executes:
   `PUT /admin/realms/swiftdrop/clients/{cid}/protocol-mappers/models/{mid}`:
   ```json
   {
     "id": "{mid}",
     "name": "devarom-api-audience",
     "protocol": "openid-connect",
     "protocolMapper": "oidc-audience-mapper",
     "config": {
       "included.custom.audience": "swiftdrop-api"
     }
   }
   ```
5. Executes post-cutover preflight and asserts 100% stable ID-keyed invariant retention.

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
# Restart Gateway
```

### 8.2 Accounts Service Configuration
Set environment variables on Accounts & Partners Service:
```bash
export KEYCLOAK_ISSUER_URI="http://localhost:8090/realms/swiftdrop"
export KEYCLOAK_JWKS_URI="http://localhost:8090/realms/swiftdrop/protocol/openid-connect/certs"
export JWT_AUDIENCE="swiftdrop-api"
# Restart Accounts Service
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

### Step 3: Emergency Keycloak Restoration (If In-Place Reversion Fails)
If container corruption occurred:
1. `docker stop devarom-keycloak -t 30`
2. Restore H2 files from the verified cold backup directory:
   `docker cp ${BACKUP_DIR}/h2/. devarom-keycloak:/opt/keycloak/data/h2/`
3. `docker start devarom-keycloak`
4. Verify health via `./platform/local/check-readiness.sh`.

> [!CAUTION]
> **NEVER EXECUTE AN EMERGENCY ACCOUNTS DATABASE RESTORE AUTOMATICALLY.**  
> Restoring the Accounts PostgreSQL database may overwrite newer transactional records created during the maintenance window.  
> Any database restore must be a separately authorized, deliberate recovery decision made in direct consultation with Accounts Steward Sinthuha based on verified data consistency needs.  
> If separately authorized and confirmed by Sinthuha:
> ```bash
> docker exec -i -e PGPASSWORD=accounts_dev_password devarom-accounts-postgres pg_restore -U accounts_app -d accounts_db --clean < ${BACKUP_DIR}/accounts_backup_*.dump
> ```

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
