#!/usr/bin/env bash
# ==============================================================================
# SwiftDrop Platform — Local Infrastructure Startup Helper (Nov 1 Milestone)
# Starts Docker containers (Keycloak, Mailpit, PostgreSQL), waits for readiness,
# and verifies runtime policies. Safe and non-destructive.
# ==============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

echo "=============================================================================="
echo " SwiftDrop Platform — Local Infrastructure Startup"
echo "=============================================================================="
echo "Repository Root: ${REPO_ROOT}"
echo ""

# 1. Check local .env file
IDENTITY_ENV="${REPO_ROOT}/platform/identity/.env"
if [ ! -f "${IDENTITY_ENV}" ]; then
  echo "WARNING: ${IDENTITY_ENV} does not exist."
  echo "Creating from .env.example..."
  cp "${REPO_ROOT}/platform/identity/.env.example" "${IDENTITY_ENV}"
  echo "Please set KC_BOOTSTRAP_ADMIN_PASSWORD in ${IDENTITY_ENV} before proceeding."
  exit 1
fi

# Ensure KC_BOOTSTRAP_ADMIN_PASSWORD is set and non-empty
if ! grep -q "^KC_BOOTSTRAP_ADMIN_PASSWORD=.\+" "${IDENTITY_ENV}"; then
  echo "ERROR: KC_BOOTSTRAP_ADMIN_PASSWORD is empty in ${IDENTITY_ENV}."
  echo "Please edit ${IDENTITY_ENV} and set an explicit local admin password."
  exit 1
fi

# 2. Start Keycloak and Mailpit Containers
echo "[1/4] Starting Keycloak and Mailpit containers..."
if docker ps -a --format '{{.Names}}' | grep -qx "devarom-keycloak"; then
  echo "  Existing 'devarom-keycloak' container detected (preserving internal database and state)..."
  if ! docker ps --format '{{.Names}}' | grep -qx "devarom-keycloak"; then
    echo "  Starting stopped 'devarom-keycloak' container..."
    docker start devarom-keycloak >/dev/null
  else
    echo "  'devarom-keycloak' is already running."
  fi
  # Ensure mailpit is also running
  if docker ps -a --format '{{.Names}}' | grep -qx "devarom-mailpit"; then
    if ! docker ps --format '{{.Names}}' | grep -qx "devarom-mailpit"; then
      docker start devarom-mailpit >/dev/null
    fi
  else
    docker compose -f "${REPO_ROOT}/platform/identity/docker-compose.identity.yml" \
      --project-directory "${REPO_ROOT}/platform/identity" up -d mailpit
  fi
else
  echo "  Creating fresh Keycloak and Mailpit containers from docker-compose.identity.yml..."
  docker compose -f "${REPO_ROOT}/platform/identity/docker-compose.identity.yml" \
    --project-directory "${REPO_ROOT}/platform/identity" up -d
fi

# 3. Start or Create Accounts PostgreSQL Container
echo ""
echo "[2/4] Starting Accounts PostgreSQL container..."
discover_postgres_port() {
  if [ -n "${ACCOUNTS_DB_PORT:-}" ]; then
    echo "${ACCOUNTS_DB_PORT}"
    return
  fi
  if [ -n "${ACCOUNTS_DB_URL:-}" ]; then
    local parsed
    parsed=$(echo "${ACCOUNTS_DB_URL}" | grep -o ':[0-9]\+/' | tr -d ':/' || true)
    if [ -n "${parsed}" ]; then
      echo "${parsed}"
      return
    fi
  fi
  if command -v docker >/dev/null 2>&1; then
    local mapped
    mapped=$(docker port devarom-accounts-postgres 5432 2>/dev/null | head -n 1 | awk -F: '{print $NF}' || true)
    if [ -n "${mapped}" ]; then
      echo "${mapped}"
      return
    fi
  fi
  if lsof -i :5438 -sTCP:LISTEN >/dev/null 2>&1; then
    echo "5438"
    return
  fi
  echo "5432"
}

PG_PORT="$(discover_postgres_port)"
PG_PASSWORD="${ACCOUNTS_DB_PASSWORD:-accounts_dev_password}"
PG_USER="${ACCOUNTS_DB_USERNAME:-accounts_app}"

if docker ps -a --format '{{.Names}}' | grep -qx "devarom-accounts-postgres"; then
  echo "  Starting existing 'devarom-accounts-postgres' container..."
  docker start devarom-accounts-postgres >/dev/null
else
  echo "  Creating dedicated 'devarom-accounts-postgres' container on port ${PG_PORT}..."
  docker run -d --name devarom-accounts-postgres \
    -e POSTGRES_USER="${PG_USER}" \
    -e POSTGRES_DB=accounts_db \
    -e POSTGRES_PASSWORD="${PG_PASSWORD}" \
    -p "127.0.0.1:${PG_PORT}:5432" \
    -v devarom-accounts-postgres-data:/var/lib/postgresql/data \
    postgres:16-alpine >/dev/null
fi
echo "  [OK] PostgreSQL container ready on port ${PG_PORT}."

# 4. Wait for Keycloak OIDC Discovery
echo ""
KEYCLOAK_REALM="${KEYCLOAK_REALM:-swiftdrop}"
echo "[3/4] Waiting for Keycloak realm discovery on http://localhost:8090 (realm: ${KEYCLOAK_REALM})..."
RETRIES=30
until curl -s "http://localhost:8090/realms/${KEYCLOAK_REALM}/.well-known/openid-configuration" | grep -q '"issuer":' || [ "${RETRIES}" -eq 0 ]; do
  sleep 2
  RETRIES=$((RETRIES - 1))
done

if [ "${RETRIES}" -eq 0 ]; then
  echo "ERROR: Keycloak did not become ready within the timeout period."
  exit 1
fi
echo "  [OK] Keycloak OIDC discovery is responsive."

# 5. Check and Apply Runtime Policies (Theme, Email Verification, TOTP MFA)
echo ""
echo "[4/4] Verifying identity runtime configurations..."
"${REPO_ROOT}/platform/identity/apply-theme.sh" || true
python3 "${REPO_ROOT}/platform/identity/configure-email-verification.py" check || \
  python3 "${REPO_ROOT}/platform/identity/configure-email-verification.py" apply
python3 "${REPO_ROOT}/platform/identity/configure-totp.py" check || \
  python3 "${REPO_ROOT}/platform/identity/configure-totp.py" apply

echo ""
echo "=============================================================================="
echo " Infrastructure containers are ready."
echo " Next: Start the microservices and frontend in separate terminals:"
echo ""
echo "  Terminal 1 (Accounts Service):"
echo "    cd services/accounts-service"
echo "    export ACCOUNTS_DB_URL=jdbc:postgresql://localhost:${PG_PORT}/accounts_db"
echo "    export ACCOUNTS_DB_USERNAME=\${PG_USER}"
echo "    export ACCOUNTS_DB_PASSWORD=\${PG_PASSWORD}"
echo "    ./mvnw spring-boot:run"
echo ""
echo "  Terminal 2 (API Gateway):"
echo "    cd services/api-gateway"
echo "    export ACCOUNTS_SERVICE_URL=http://localhost:8081"
echo "    ./mvnw spring-boot:run"
echo ""
echo "  Terminal 3 (React SPA):"
echo "    cd frontend"
echo "    pnpm install --frozen-lockfile"
echo "    pnpm dev"
echo ""
echo " Run readiness check anytime: ./platform/local/check-readiness.sh"
echo "=============================================================================="
