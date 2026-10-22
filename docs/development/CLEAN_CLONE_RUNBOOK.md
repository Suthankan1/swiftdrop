# SwiftDrop — Clean-Clone Setup, Verification & Demonstration Runbook

**Milestone:** November 1, 2026 Checkpoint (Report + Initial Working Logins)  
**Target Integration Branch:** `test`  
**Relevant Issues:** Issue #17 (Clean-Clone QA Verification), Issue #18 (Security E2E Acceptance), Issue #19 (Report & Handoff)  
**Platform Lead:** Suthankan1  
**Independent Clean-Clone Reviewer:** Eric (@EricPraveen)  

---

## 1. Prerequisites & Required Software Versions

Verify your host environment meets the pinned SwiftDrop baseline before starting:

| Tool | Required Version | Verification Command | Notes |
|---|---|---|---|
| **Git** | 2.40+ | `git --version` | Standard git client |
| **Docker** | 24+ / Compose v2 | `docker --version && docker compose version` | Docker Desktop or Docker Engine daemon must be running |
| **Java Platform** | **Java 21 LTS** | `java -version` | Recommended: OpenJDK 21 LTS (`export JAVA_HOME=$(/usr/libexec/java_home -v 21)` on macOS) |
| **Node.js** | **Node 24 LTS** (or 20+) | `node --version` | Pinned in `.node-version` (v24.21.0) |
| **pnpm** | **12.4+ / 12.11.2** | `pnpm --version` | Installed globally or enabled via `corepack enable` |
| **Python 3** | **3.10+** (tested on 3.14) | `python3 --version` | Standard library + `pip install cryptography` (for synthetic RSA test signatures in security E2E) |

### Host Port Allocation
Ensure the following ports are available on `localhost`:
- `5173` — React SPA (Vite Dev Server)
- `8080` — Spring Cloud API Gateway
- `8081` — Accounts & Partners Service
- `8090` — Keycloak 26.8.1 Container
- `8025` — Mailpit Development SMTP Web UI (`1025` internal SMTP)
- `5438` (or `5432`) — Dedicated PostgreSQL 16 Alpine container for Accounts Service
- `5439` — Dedicated PostgreSQL 16 Alpine container for Business Services (Pricing, Delivery, Communications)
- `9092` — Apache Kafka KRaft Broker
- `9000` / `9001` — Private Object Storage S3 API / Console (MinIO)

---

## 2. Fresh Clone & Environment Bootstrap

### 2.1 Clone the Active Integration Branch
Always clone or checkout the `test` branch (all feature integration occurs on `test`, never directly on `main`):

```bash
git clone -b test https://github.com/Suthankan1/swiftdrop.git
cd swiftdrop
```

### 2.2 Configure Local Identity Secrets (Ignored by Git)
Copy the template and configure your local administrator password:

```bash
cp platform/identity/.env.example platform/identity/.env
```

Edit `platform/identity/.env` and supply an explicit local administrator password:
```properties
KC_BOOTSTRAP_ADMIN_USERNAME=admin
KC_BOOTSTRAP_ADMIN_PASSWORD=your_secure_local_password_here
KEYCLOAK_PORT=8090
KC_HOSTNAME=http://localhost:8090
MAILPIT_UI_PORT=8025
SMTP_HOST=mailpit
SMTP_PORT=1025
SMTP_FROM=no-reply@swiftdrop.test
SMTP_FROM_DISPLAY_NAME=SwiftDrop
SMTP_AUTH=false
SMTP_SSL=false
SMTP_STARTTLS=false
```

> [!IMPORTANT]
> - Never commit `platform/identity/.env` or any password to Git.
> - `.gitignore` strictly protects `.env`, `.env.*`, and `secrets/`.
> - Google OAuth credentials (`GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`) are optional for local development. If not provided, Keycloak local password authentication, email verification, and TOTP MFA remain 100% operational.

### 2.3 Frontend Environment (Public Tokens)
The frontend connects to standard ports by default. If customization is required:
```bash
cp frontend/.env.example frontend/.env
```
Contains public non-secret URLs:
```properties
VITE_KEYCLOAK_URL=http://localhost:8090
VITE_API_GATEWAY_URL=http://localhost:8080
```

---

## 3. Service Startup Procedure (Ordered & Non-Destructive)

To start the system reliably, follow the precise dependency order:

```text
1. PostgreSQL (5438/5432) ──► 2. Keycloak (8090) & Mailpit (8025) ──► 3. Identity Runtime Config
                                                                                │
4. React SPA (5173) ◄──────── 5. API Gateway (8080) ◄──────── 6. Accounts (8081)
```

### Option A: Automated Container Startup Helper (Recommended)
You can start steps 1 through 3 automatically using the platform startup script:

```bash
./platform/local/start-local.sh
```

This helper:
1. Validates your `platform/identity/.env` file.
2. Starts Keycloak (`:8090`) and Mailpit (`:8025`) via Docker Compose.
3. Starts (or non-destructively provisions) `swiftdrop-accounts-postgres` (or legacy `devarom-accounts-postgres`) on port `5438`.
4. Waits for Keycloak OIDC discovery to respond.
5. Verifies and applies the branded theme, native email verification required action, and TOTP MFA policy.

---

### Option B: Manual Step-by-Step Startup

If you prefer starting each component manually:

#### Step 1: Start Accounts PostgreSQL Container
```bash
# Check if container already exists (swiftdrop-accounts-postgres or legacy devarom-accounts-postgres)
if docker ps -a --format '{{.Names}}' | grep -qx "swiftdrop-accounts-postgres"; then
  docker start swiftdrop-accounts-postgres
elif docker ps -a --format '{{.Names}}' | grep -qx "devarom-accounts-postgres"; then
  docker start devarom-accounts-postgres
else
  docker run -d --name swiftdrop-accounts-postgres \
    -e POSTGRES_USER=accounts_app \
    -e POSTGRES_DB=accounts_db \
    -e POSTGRES_PASSWORD=accounts_dev_password \
    -p 127.0.0.1:5438:5432 \
    -v swiftdrop-accounts-postgres-data:/var/lib/postgresql/data \
    postgres:16-alpine
fi
```

#### Step 2: Start Keycloak & Mailpit Containers
```bash
docker compose -f platform/identity/docker-compose.identity.yml \
  --project-directory platform/identity up -d
```

Wait ~15 seconds until Keycloak discovery responds:
```bash
curl -s http://localhost:8090/realms/swiftdrop/.well-known/openid-configuration | grep '"issuer":'
```

#### Step 3: Apply Theme & Runtime Configurations
```bash
# 1. Apply branded theme
./platform/identity/apply-theme.sh

# 2. Configure native email verification link policy (Mailpit capture, 60s cooldown)
python3 platform/identity/configure-email-verification.py apply

# 3. Configure native authenticator-app TOTP MFA policy (RFC 6238, 6 digits, 30s)
python3 platform/identity/configure-totp.py apply
```

---

### Step 4: Start Backend Microservices

In **Terminal 1** (Accounts & Partners Service):
```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21) # macOS
cd services/accounts-service
export ACCOUNTS_DB_URL=jdbc:postgresql://localhost:5438/accounts_db
export ACCOUNTS_DB_USERNAME=accounts_app
export ACCOUNTS_DB_PASSWORD=accounts_dev_password
./mvnw spring-boot:run
```
*Listens on port 8081. Flyway V1 migration applies automatically on startup.*

In **Terminal 2** (Spring Cloud API Gateway):
```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21) # macOS
cd services/api-gateway
export ACCOUNTS_SERVICE_URL=http://localhost:8081
./mvnw spring-boot:run
```
*Listens on port 8080.*

---

### Step 5: Start React Frontend SPA

In **Terminal 3** (React SPA):
```bash
cd frontend
pnpm install --frozen-lockfile
pnpm dev
```
*Serves the SPA on `http://localhost:5173`.*

---

### Step 6: Start Business Microservices Dependencies (Issue #91)

For developers working on Pricing & Zones, Delivery Operations, or Communications microservices, start Kafka KRaft, Business PostgreSQL, and Private S3 storage:

```bash
./platform/dependencies/start-dependencies.sh
```

Run readiness validation:
```bash
./platform/dependencies/check-dependencies.sh
```

Run automated smoke & persistence test suite:
```bash
./platform/dependencies/smoke-dependencies.sh
```

---

## 4. Service Readiness Verification

Execute the preflight readiness check tool from the repository root:

```bash
./platform/local/check-readiness.sh
```

Expected Output:
```text
[1/4] Checking Developer Toolchain Prerequisites...
  [PASS] Git installed
  [PASS] Docker daemon is running
  [PASS] Java 21+ detected
  [PASS] Node.js detected
  [PASS] pnpm package manager detected
  [PASS] Python 3 detected

[2/4] Checking Container Infrastructure Services...
  [PASS] Keycloak Container is listening on :8090 and realm 'swiftdrop' OIDC discovery succeeded
  [PASS] Mailpit SMTP UI is healthy on port 8025 (HTTP 200)
  [PASS] PostgreSQL container (swiftdrop/devarom-accounts-postgres) is listening on port 5438

[3/4] Checking Microservices and Frontend Application...
  [PASS] Accounts & Partners Service is UP on port 8081 (database connectivity verified)
  [PASS] API Gateway is UP on port 8080 (edge routing & health verified)
  [PASS] React Frontend SPA is serving HTTP 200 on http://localhost:5173/

[4/4] Checking Identity Security Policies & Configurations...
  [PASS] Identity invariants & live Keycloak configuration passed (theme, email verify, TOTP MFA)

READINESS CHECK SUMMARY: 13 Passed, 0 Warnings, 0 Failures.
✓ ALL LOCAL SERVICES AND PREREQUISITES ARE READY FOR DEMO REHEARSAL.
```

---

## 5. Live Demonstration Sequence (Nov 1 Checkpoint)

Follow this sequence for live demonstrations (e.g. lecturer presentation or peer QA verification):

### Demonstration 1: Automated Full-Stack Smoke Tests
Run the automated end-to-end demo runner:
```bash
python3 tests/security-e2e/demo_smoke.py
```
This script exercises:
- **Scenario A:** Self-registers a disposable customer with S256 PKCE challenge, intercepts verification email in Mailpit, verifies email via native link, completes deferred password, exchanges code for JWT, accesses `/api/v1/users/me` profile through Gateway to Accounts, and cleans up session.
- **Scenario B:** Authenticates an existing verified customer, confirms `/customer` profile displays user's name, email, and role, and tests RP-initiated logout.
- **Scenario C:** Tests RFC 6238 TOTP MFA authentication challenge (`login-otp`) and verifies protected access with MFA token.
- **Scenario D:** Proves negative security controls: unauthenticated requests (401), malformed tokens (401), untrusted signatures (401), and cross-user isolation.

---

### Demonstration 2: Interactive Browser Walkthrough

Open your web browser at `http://localhost:5173`:

#### A. New Customer Registration & Email Verification
1. Navigate to `http://localhost:5173/login`.
2. Click **Create account**.
3. Fill in the branded Keycloak registration form:
   - First Name: `Demo`
   - Last Name: `Customer`
   - Email: `demo.customer@swiftdrop.test`
   - Password: `DemoPassword123!`
   - Confirm Password: `DemoPassword123!`
4. Click **Register**. Keycloak will display the verification pending notice ("You need to verify your email address to activate your account").
5. Open Mailpit in a new browser tab: `http://localhost:8025`.
6. Click the newly arrived email from `no-reply@swiftdrop.test`.
7. Click the verification link in the email.
8. Complete password confirmation if prompted.
9. Browser redirects through `/auth/callback` back to `http://localhost:5173/customer`.
10. Confirm that the Customer Space renders:
    - Status: Ready
    - Full Name: `Demo Customer`
    - Email: `demo.customer@swiftdrop.test`
    - Roles: `CUSTOMER` (strictly confined; zero staff roles)
    - Database Profile UUID generated by Accounts Service.
11. Click **Sign out**. Confirm you are returned to the public landing page and token is cleared from memory.

#### B. Existing Customer Login
1. Navigate to `http://localhost:5173/login`.
2. Click **Sign in**.
3. Enter credentials:
   - Username / Email: `demo.customer@swiftdrop.test`
   - Password: `DemoPassword123!`
4. Redirects to `/customer` showing user's profile.
5. Click **Sign out**.

#### C. Negative Security Probes in Terminal
Demonstrate that the API Gateway and Accounts Service fail closed:
```bash
# 1. Edge rejection: Missing token returns 401 ProblemDetail
curl -i http://localhost:8080/api/v1/users/me

# 2. Downstream rejection: Direct Accounts access without token returns 401
curl -i http://localhost:8081/api/v1/users/me

# 3. Spoofed headers stripped: Gateway strips client-forged headers
curl -i -H "X-User-Id: evil-id" -H "X-Roles: ADMIN" http://localhost:8080/api/v1/users/me
```

---

## 6. Full Security E2E Test Suite Execution

Run the master security and regression orchestrator:

```bash
./tests/security-e2e/run-security-e2e.sh
```

**Verification Results:**
- **Phase 1:** Preflight service readiness checks (all 5 services detected).
- **Phase 2:** 40 Security E2E scenarios:
  - 36 PASSED
  - 0 FAILED
  - 4 BLOCKED (tracked dependencies on unimplemented teammate endpoints: Issues #8, #14, #15).
- **Phase 3:** Identity & MFA regressions (`validate-identity.sh`, `totp_smoke.py`).
- **Phase 4:** Service-level test suites:
  - Gateway: 13 tests passed
  - Accounts: 37 unit/MVC tests passed (53 total with IT)
  - Frontend: 56 unit/integration tests passed

---

## 7. Troubleshooting & Common Issues

| Issue / Error | Root Cause | Solution |
|---|---|---|
| `Port 5432 or 5438 already in use` | Another PostgreSQL instance running | Check `lsof -i :5438` or `lsof -i :5432`. If needed, specify `ACCOUNTS_DB_PORT=5439` and re-run `./platform/local/start-local.sh`. |
| `KC_BOOTSTRAP_ADMIN_PASSWORD must be explicitly set` | Missing or blank password in `platform/identity/.env` | Edit `platform/identity/.env` and supply a non-empty string. |
| `Keycloak connection refused on :8090` | Docker daemon stopped or container crashed | Run `docker ps` and check logs: `docker logs swiftdrop-keycloak` (or `devarom-keycloak`). |
| `Accounts Service fail to connect to DB` | Wrong DB port or container not running | Check `docker ps` for `swiftdrop-accounts-postgres` (or `devarom-accounts-postgres`). Verify `ACCOUNTS_DB_URL` matches the mapped port. |
| `An earlier recovery snapshot exists` | Stale recovery file in `.local/` | Remove stale file: `rm platform/identity/.local/email-verification-recovery.json` and re-run `./platform/identity/configure-email-verification.py apply`. |
| `mounted volume is marked read-only` | Theme volume mounted `:ro` in Compose | Handled automatically by updated `apply-theme.sh`. Volume mount already contains live theme files. |

---

## 8. Teardown & Environment Reset

To stop running processes and containers cleanly:

```bash
# 1. Stop background microservices (Ctrl+C in terminal 1, 2, 3)

# 2. Stop container infrastructure
docker compose -f platform/identity/docker-compose.identity.yml \
  --project-directory platform/identity down

# 3. Stop PostgreSQL container
docker stop swiftdrop-accounts-postgres 2>/dev/null || docker stop devarom-accounts-postgres
```

> [!NOTE]
> Database volumes (`swiftdrop-accounts-postgres-data` or legacy `devarom-accounts-postgres-data`) and Keycloak imports are preserved across container stops. Do not delete volumes unless you explicitly wish to start from an empty database.
