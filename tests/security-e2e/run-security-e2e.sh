#!/usr/bin/env bash
# ==============================================================================
# Devarom Platform — Security E2E Test Orchestrator (Task 018)
# Reproducible execution of L06, L07, and L08 security tests and regressions.
# For independent QA verification by Vibhushan (@Vibhushan-2003) and teammates.
# ==============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

echo "=============================================================================="
echo " Devarom Platform — Security E2E Test Automation (Task 018)"
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

check_port 8090 "Keycloak Identity Container"
check_port 8080 "Spring Cloud Gateway"
check_port 8081 "Accounts & Partners Service"
check_port 8025 "Mailpit SMTP Web/API"
check_port 5438 "PostgreSQL Container (devarom-accounts-postgres)"

if [ "${FAILED_SERVICES}" -gt 0 ]; then
  echo ""
  echo "ERROR: One or more required local services are not running."
  echo "Prerequisites startup reference:"
  echo "  1. Identity & Mailpit : docker compose -f platform/identity/docker-compose.identity.yml up -d"
  echo "  2. Accounts DB        : docker start devarom-accounts-postgres (mapped on port 5438)"
  echo "  3. Accounts Service   : cd services/accounts-service && ACCOUNTS_DB_URL=jdbc:postgresql://localhost:5438/accounts_db ACCOUNTS_DB_USERNAME=accounts_app ACCOUNTS_DB_PASSWORD=accounts_dev_password ./mvnw spring-boot:run"
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
(cd "${REPO_ROOT}/services/api-gateway" && ./mvnw test -q)
echo "      -> Gateway tests passed."

echo "  [+] Running Accounts Service OAuth2 Resource Server unit & MVC tests..."
(cd "${REPO_ROOT}/services/accounts-service" && ./mvnw test -q)
echo "      -> Accounts tests passed."

echo "  [+] Running Frontend token persistence & route guard tests..."
(cd "${REPO_ROOT}/frontend" && pnpm test --run --silent)
echo "      -> Frontend tests passed."

echo ""
echo "=============================================================================="
echo " ALL DEVAROM SECURITY E2E AND REGRESSION TESTS COMPLETED SUCCESSFULLY"
echo "=============================================================================="
echo "Artifact: ${SCRIPT_DIR}/reports/security_e2e_results.json"
echo "Ready for independent reproduction by QA Owner Vibhushan (@Vibhushan-2003)."
echo "=============================================================================="
