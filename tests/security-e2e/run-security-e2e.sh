#!/usr/bin/env bash
# ==============================================================================
# SwiftDrop Platform — Security E2E Test Orchestrator (Task 018)
# Reproducible execution of L06, L07, and L08 security tests and regressions.
# Executed by: Suthankan1 (Platform Self-Verification)
# Original QA Owner: Vibhushan (@Vibhushan-2003) (Independent Review Pending)
# ==============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

echo "=============================================================================="
echo " SwiftDrop Platform — Security E2E Test Automation (Task 018)"
echo "=============================================================================="
echo "Repository Root : ${REPO_ROOT}"
echo "Execution Time  : $(date -u '+%Y-%m-%dT%H:%M:%SZ')"
echo "=============================================================================="

# 1. Environment Readiness Verification
echo ""
echo "[Phase 1/4] Preflight Service Readiness Checks..."
FAILED_SERVICES=0

check_port() {
  local port="$1"
  local name="$2"
  if lsof -i ":${port}" -sTCP:LISTEN >/dev/null 2>&1; then
    echo "  [OK] ${name} is listening on port ${port}."
  else
    echo "  [MISSING] ${name} is NOT listening on port ${port}."
    FAILED_SERVICES=$((FAILED_SERVICES + 1))
  fi
}

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
    for c in swiftdrop-accounts-postgres devarom-accounts-postgres; do
      mapped=$(docker port "$c" 5432 2>/dev/null | head -n 1 | awk -F: '{print $NF}' || true)
      if [ -n "${mapped}" ]; then
        echo "${mapped}"
        return
      fi
    done
  fi
  if lsof -i :5438 -sTCP:LISTEN >/dev/null 2>&1; then
    echo "5438"
    return
  fi
  echo "5432"
}

PG_PORT="$(discover_postgres_port)"

check_port 8090 "Keycloak Identity Container"
check_port 8080 "Spring Cloud Gateway"
check_port 8081 "Accounts & Partners Service"
check_port 8025 "Mailpit SMTP Web/API"
check_port "${PG_PORT}" "PostgreSQL Container (swiftdrop/devarom-accounts-postgres on port ${PG_PORT})"

if lsof -i :5173 -sTCP:LISTEN >/dev/null 2>&1; then
  echo "  [OK] React SPA is listening on port 5173."
else
  echo "  [INFO] React SPA (port 5173) not running (optional for backend security tests)."
fi

if [ "${FAILED_SERVICES}" -gt 0 ]; then
  echo ""
  echo "ERROR: One or more required local services are not running."
  echo "Prerequisites startup reference:"
  echo "  1. Identity & Mailpit : docker compose -f platform/identity/docker-compose.identity.yml up -d"
  echo "  2. Accounts DB        : docker start swiftdrop-accounts-postgres (or devarom-accounts-postgres, mapped on port ${PG_PORT})"
  echo "  3. Accounts Service   : cd services/accounts-service && ACCOUNTS_DB_URL=jdbc:postgresql://localhost:${PG_PORT}/accounts_db ACCOUNTS_DB_USERNAME=accounts_app ACCOUNTS_DB_PASSWORD=<password> ./mvnw spring-boot:run"
  echo "  4. Gateway Service    : cd services/api-gateway && ACCOUNTS_SERVICE_URL=http://localhost:8081 ./mvnw spring-boot:run"
  exit 1
fi

# 2. Run Python Security E2E Test Suite (L06, L07, L08)
echo ""
echo "[Phase 2/4] Executing End-to-End Security Rejection & Isolation Suite..."
python3 "${SCRIPT_DIR}/security_e2e_runner.py"

# 3. Run Platform Identity & Regression Suites
echo ""
echo "[Phase 3/4] Executing Platform Identity & MFA Regression Suites..."
"${REPO_ROOT}/platform/identity/validate-identity.sh"
python3 "${REPO_ROOT}/platform/identity/tests/totp_smoke.py"

# 4. Service Unit & MVC Security Regressions
echo ""
echo "[Phase 4/4] Executing Service-Level Unit & Integration Security Regressions..."
echo "  [+] Running API Gateway unit & routing security tests..."
(cd "${REPO_ROOT}/services/api-gateway" && env -u KEYCLOAK_ISSUER_URI -u KEYCLOAK_JWKS_URI -u JWT_AUDIENCE ./mvnw test -q)
echo "      -> Gateway tests passed."

echo "  [+] Running Accounts Service OAuth2 Resource Server unit & MVC tests..."
(cd "${REPO_ROOT}/services/accounts-service" && env -u KEYCLOAK_ISSUER_URI -u KEYCLOAK_JWKS_URI -u JWT_AUDIENCE ./mvnw test -q)
echo "      -> Accounts tests passed."

echo "  [+] Running Frontend token persistence & route guard tests..."
(cd "${REPO_ROOT}/frontend" && pnpm test --run --silent)
echo "      -> Frontend tests passed."

echo ""
echo "=============================================================================="
echo " ALL SWIFTDROP SECURITY E2E AND REGRESSION TESTS COMPLETED SUCCESSFULLY"
echo "=============================================================================="
echo "Artifact: ${SCRIPT_DIR}/reports/security_e2e_results.json"
echo "Classification: Platform Self-Verification (Executed by Suthankan1)"
echo "Independent QA Reproduction: Pending Vibhushan (@Vibhushan-2003)"
echo "=============================================================================="
