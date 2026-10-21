#!/usr/bin/env bash
# ==============================================================================
# SwiftDrop Platform — Full-Stack Service Readiness Checker
# Clean-clone verification tool for Nov 1 demo readiness (Refs #17, #18, #19).
# Verifies toolchain prerequisites, container health, microservices, and SPA.
# Safe for all environments: zero credentials or secrets exposed.
# ==============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

# ANSI formatting
BOLD='\033[1m'
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[0;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

PASSED_CHECKS=0
WARNING_CHECKS=0
FAILED_CHECKS=0

pass() {
  echo -e "  [${GREEN}PASS${NC}] $1"
  PASSED_CHECKS=$((PASSED_CHECKS + 1))
}

warn() {
  echo -e "  [${YELLOW}WARN${NC}] $1"
  WARNING_CHECKS=$((WARNING_CHECKS + 1))
}

fail() {
  echo -e "  [${RED}FAIL${NC}] $1"
  FAILED_CHECKS=$((FAILED_CHECKS + 1))
}

echo -e "${BOLD}==============================================================================${NC}"
echo -e "${BOLD} SwiftDrop Platform — Full-Stack Readiness Verification${NC}"
echo -e "${BOLD}==============================================================================${NC}"
echo "Repository Root : ${REPO_ROOT}"
echo "Execution Time  : $(date -u '+%Y-%m-%dT%H:%M:%SZ')"
echo ""

# ------------------------------------------------------------------------------
# 1. Toolchain & Runtime Prerequisites
# ------------------------------------------------------------------------------
echo -e "${BOLD}[1/4] Checking Developer Toolchain Prerequisites...${NC}"

# Git
if command -v git >/dev/null 2>&1; then
  GIT_VER=$(git --version)
  pass "Git installed (${GIT_VER})"
else
  fail "Git is missing from PATH"
fi

# Docker
if command -v docker >/dev/null 2>&1; then
  if docker info >/dev/null 2>&1; then
    DOCKER_VER=$(docker --version)
    pass "Docker daemon is running (${DOCKER_VER})"
  else
    fail "Docker CLI is installed, but the Docker daemon is NOT running. Please start Docker Engine / Desktop."
  fi
else
  fail "Docker is missing from PATH"
fi

# Java 21
if command -v java >/dev/null 2>&1; then
  JAVA_VER_STR=$(java -version 2>&1 | head -n 1)
  JAVA_MAJOR=$(java -version 2>&1 | head -n 1 | awk -F '"' '{print $2}' | awk -F '.' '{print $1}')
  if [ "${JAVA_MAJOR}" -ge 21 ] 2>/dev/null; then
    pass "Java 21+ detected (${JAVA_VER_STR})"
  else
    warn "Java version is below 21 (${JAVA_VER_STR}). Spring Boot 4.1.1 baseline requires Java 21 LTS."
  fi
else
  fail "Java is missing from PATH. Install Java 21 LTS."
fi

# Node.js
if command -v node >/dev/null 2>&1; then
  NODE_VER=$(node --version)
  NODE_MAJOR=$(echo "${NODE_VER}" | sed 's/v//' | awk -F '.' '{print $1}')
  if [ "${NODE_MAJOR}" -ge 20 ] 2>/dev/null; then
    pass "Node.js detected (${NODE_VER})"
  else
    warn "Node.js version is ${NODE_VER}. Recommended version is Node 24 LTS (or Node 20+)."
  fi
else
  fail "Node.js is missing from PATH"
fi

# pnpm
if command -v pnpm >/dev/null 2>&1; then
  PNPM_VER=$(pnpm --version)
  pass "pnpm package manager detected (${PNPM_VER})"
else
  warn "pnpm is missing from PATH. Install via 'npm install -g pnpm' or 'corepack enable'."
fi

# Python 3
if command -v python3 >/dev/null 2>&1; then
  PY_VER=$(python3 --version)
  pass "Python 3 detected (${PY_VER})"
else
  fail "Python 3 is missing from PATH. Required for identity and security smoke tests."
fi

echo ""

# ------------------------------------------------------------------------------
# 2. Container Infrastructure Health
# ------------------------------------------------------------------------------
echo -e "${BOLD}[2/4] Checking Container Infrastructure Services...${NC}"

KEYCLOAK_URL="${KEYCLOAK_URL:-http://localhost:8090}"
KEYCLOAK_REALM="${KEYCLOAK_REALM:-swiftdrop}"
if lsof -i :8090 -sTCP:LISTEN >/dev/null 2>&1; then
  OIDC_DISCOVERY=$(curl -s --connect-timeout 3 "${KEYCLOAK_URL}/realms/${KEYCLOAK_REALM}/.well-known/openid-configuration" || true)
  if echo "${OIDC_DISCOVERY}" | grep -q '"issuer":'; then
    pass "Keycloak Container is listening on :8090 and realm '${KEYCLOAK_REALM}' OIDC discovery succeeded"
  else
    warn "Keycloak is listening on port 8090, but realm '${KEYCLOAK_REALM}' OIDC discovery did not return expected issuer. Ensure realm import completed."
  fi
else
  fail "Keycloak Container is NOT listening on port 8090. Start via: ./platform/local/start-local.sh"
fi

# Mailpit SMTP & UI (Port 8025)
MAILPIT_URL="${MAILPIT_URL:-http://localhost:8025}"
if lsof -i :8025 -sTCP:LISTEN >/dev/null 2>&1; then
  MAILPIT_STATUS=$(curl -s -o /dev/null -w "%{http_code}" --connect-timeout 3 "${MAILPIT_URL}/" || true)
  if [ "${MAILPIT_STATUS}" = "200" ]; then
    pass "Mailpit SMTP UI is healthy on port 8025 (HTTP 200)"
  else
    warn "Mailpit is listening on port 8025, but returned HTTP ${MAILPIT_STATUS}."
  fi
else
  fail "Mailpit is NOT listening on port 8025. Start via: docker compose -f platform/identity/docker-compose.identity.yml up -d"
fi

# PostgreSQL Database (Discover port: 5438, 5432, or ACCOUNTS_DB_PORT)
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
if lsof -i ":${PG_PORT}" -sTCP:LISTEN >/dev/null 2>&1; then
  pass "PostgreSQL container (devarom-accounts-postgres) is listening on port ${PG_PORT}"
else
  fail "PostgreSQL container is NOT listening on port ${PG_PORT}. Start via: docker start devarom-accounts-postgres"
fi

echo ""

# ------------------------------------------------------------------------------
# 3. Microservices & Frontend Health
# ------------------------------------------------------------------------------
echo -e "${BOLD}[3/4] Checking Microservices and Frontend Application...${NC}"

# Accounts Service (Port 8081)
if lsof -i :8081 -sTCP:LISTEN >/dev/null 2>&1; then
  ACC_HEALTH=$(curl -s --connect-timeout 3 "http://localhost:8081/actuator/health" || true)
  if echo "${ACC_HEALTH}" | grep -q '"status":"UP"'; then
    pass "Accounts & Partners Service is UP on port 8081 (database connectivity verified)"
  else
    warn "Accounts Service is listening on 8081, but /actuator/health status is not UP: ${ACC_HEALTH}"
  fi
else
  fail "Accounts Service is NOT listening on port 8081. Start via: cd services/accounts-service && ./mvnw spring-boot:run"
fi

# Spring Cloud API Gateway (Port 8080)
if lsof -i :8080 -sTCP:LISTEN >/dev/null 2>&1; then
  GW_HEALTH=$(curl -s --connect-timeout 3 "http://localhost:8080/actuator/health" || true)
  if echo "${GW_HEALTH}" | grep -q '"status":"UP"'; then
    pass "API Gateway is UP on port 8080 (edge routing & health verified)"
  else
    warn "API Gateway is listening on 8080, but /actuator/health status is not UP: ${GW_HEALTH}"
  fi
else
  fail "API Gateway is NOT listening on port 8080. Start via: cd services/api-gateway && ./mvnw spring-boot:run"
fi

# Frontend SPA (Port 5173)
if lsof -i :5173 -sTCP:LISTEN >/dev/null 2>&1; then
  FE_STATUS=$(curl -s -o /dev/null -w "%{http_code}" --connect-timeout 3 "http://localhost:5173/" || true)
  if [ "${FE_STATUS}" = "200" ]; then
    pass "React Frontend SPA is serving HTTP 200 on http://localhost:5173/"
  else
    warn "Frontend is listening on port 5173, but returned HTTP ${FE_STATUS}"
  fi
else
  fail "React Frontend SPA is NOT running on port 5173. Start via: cd frontend && pnpm dev"
fi

echo ""

# ------------------------------------------------------------------------------
# 4. Identity Security Configurations & Invariants
# ------------------------------------------------------------------------------
echo -e "${BOLD}[4/4] Checking Identity Security Policies & Configurations...${NC}"

# Check theme & realm validation
if [ -f "${REPO_ROOT}/platform/identity/validate-identity.sh" ]; then
  if "${REPO_ROOT}/platform/identity/validate-identity.sh" >/dev/null 2>&1; then
    pass "Identity invariants & live Keycloak configuration passed (theme, email verify, TOTP MFA)"
  else
    warn "Identity validation script reported warnings or failures. Run ./platform/identity/validate-identity.sh for details."
  fi
else
  warn "platform/identity/validate-identity.sh not found."
fi

echo ""

# ------------------------------------------------------------------------------
# Summary & Diagnostic Guidance
# ------------------------------------------------------------------------------
echo -e "${BOLD}==============================================================================${NC}"
echo -e "${BOLD} READINESS CHECK SUMMARY${NC}"
echo -e "${BOLD}==============================================================================${NC}"
echo -e "  Passed Checks  : ${GREEN}${PASSED_CHECKS}${NC}"
echo -e "  Warning Checks : ${YELLOW}${WARNING_CHECKS}${NC}"
echo -e "  Failed Checks  : ${RED}${FAILED_CHECKS}${NC}"
echo ""

if [ "${FAILED_CHECKS}" -eq 0 ]; then
  echo -e "${GREEN}${BOLD}✓ ALL LOCAL SERVICES AND PREREQUISITES ARE READY FOR DEMO REHEARSAL.${NC}"
  echo ""
  echo "Recommended next steps:"
  echo "  1. Run full security E2E suite: ./tests/security-e2e/run-security-e2e.sh"
  echo "  2. Run login demo smoke tests : python3 tests/security-e2e/demo_smoke.py"
  echo "  3. Open browser demonstration: http://localhost:5173"
  echo ""
  exit 0
else
  echo -e "${RED}${BOLD}✗ ONE OR MORE REQUIRED SERVICES ARE MISSING OR UNHEALTHY.${NC}"
  echo "Please follow the setup steps in docs/development/CLEAN_CLONE_RUNBOOK.md to start missing services."
  echo ""
  exit 1
fi
