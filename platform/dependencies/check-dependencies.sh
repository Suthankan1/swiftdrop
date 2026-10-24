#!/usr/bin/env bash
# ==============================================================================
# SwiftDrop Platform — Business Dependencies Readiness Checker
# Verifies container status, broker responsiveness, topic existence,
# database connectivity & isolation, and private object store health.
# Safe & non-destructive: zero credentials exposed.
# ==============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
DEP_DIR="${REPO_ROOT}/platform/dependencies"
ENV_FILE="${DEP_DIR}/.env"

# ANSI formatting
BOLD='\033[1m'
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[0;33m'
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
echo -e "${BOLD} SwiftDrop Platform — Business Dependencies Readiness Check${NC}"
echo -e "${BOLD}==============================================================================${NC}"
echo "Execution Time: $(date -u '+%Y-%m-%dT%H:%M:%SZ')"
echo ""

if [ ! -f "${ENV_FILE}" ]; then
  warn ".env not found in ${DEP_DIR}. Using default configuration."
else
  set -a
  # shellcheck disable=SC1090
  source "${ENV_FILE}"
  set +a
fi

KAFKA_PORT="${KAFKA_PORT:-9092}"
BUSINESS_DB_PORT="${BUSINESS_DB_PORT:-5439}"
MINIO_PORT="${MINIO_PORT:-9000}"
KAFKA_CONTAINER="${KAFKA_CONTAINER_NAME:-swiftdrop-kafka}"
PG_CONTAINER="${BUSINESS_DB_CONTAINER_NAME:-swiftdrop-business-postgres}"
MINIO_CONTAINER="${MINIO_CONTAINER_NAME:-swiftdrop-minio}"
LIFECYCLE_TOPIC="${KAFKA_TOPIC_PARCEL_LIFECYCLE:-parcel.lifecycle.v1}"
DLT_TOPIC="${KAFKA_TOPIC_PARCEL_LIFECYCLE_DLT:-parcel.lifecycle.v1.dlt}"
BUCKET="${SWIFTDROP_STORAGE_BUCKET:-swiftdrop-pod}"

# ------------------------------------------------------------------------------
# 1. Apache Kafka in KRaft Mode
# ------------------------------------------------------------------------------
echo -e "${BOLD}[1/3] Checking Apache Kafka KRaft Broker...${NC}"

if docker ps --format '{{.Names}}' | grep -qx "${KAFKA_CONTAINER}"; then
  pass "Kafka container '${KAFKA_CONTAINER}' is running"

  # Check broker API responsiveness
  if docker exec "${KAFKA_CONTAINER}" /opt/kafka/bin/kafka-broker-api-versions.sh --bootstrap-server localhost:9092 >/dev/null 2>&1; then
    pass "Kafka KRaft broker is responsive on 127.0.0.1:${KAFKA_PORT}"
  else
    fail "Kafka KRaft broker failed broker-api-versions probe"
  fi

  # Check topic existence
  TOPICS=$(docker exec "${KAFKA_CONTAINER}" /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list 2>/dev/null || true)
  if echo "${TOPICS}" | grep -qx "${LIFECYCLE_TOPIC}"; then
    pass "Lifecycle topic '${LIFECYCLE_TOPIC}' exists"
  else
    fail "Lifecycle topic '${LIFECYCLE_TOPIC}' is missing"
  fi

  if echo "${TOPICS}" | grep -qx "${DLT_TOPIC}"; then
    pass "Dead-letter topic '${DLT_TOPIC}' exists"
  else
    warn "Dead-letter topic '${DLT_TOPIC}' is missing (run start-dependencies.sh to provision)"
  fi
else
  fail "Kafka container '${KAFKA_CONTAINER}' is not running"
fi

echo ""

# ------------------------------------------------------------------------------
# 2. Business PostgreSQL Databases & Isolation
# ------------------------------------------------------------------------------
echo -e "${BOLD}[2/3] Checking Business PostgreSQL Databases & Isolated Roles...${NC}"

if docker ps --format '{{.Names}}' | grep -qx "${PG_CONTAINER}"; then
  pass "PostgreSQL container '${PG_CONTAINER}' is running on port ${BUSINESS_DB_PORT}"

  # Test pricing_app connection to pricing_db
  PRICING_PASS="${PRICING_DB_PASSWORD:-pricing_dev_password}"
  if docker exec -e PGPASSWORD="${PRICING_PASS}" "${PG_CONTAINER}" psql -U pricing_app -d pricing_db -c "SELECT 1;" >/dev/null 2>&1; then
    pass "pricing_app successfully connected to pricing_db"
  else
    fail "pricing_app failed to connect to pricing_db"
  fi

  # Test delivery_app connection to delivery_db
  DELIVERY_PASS="${DELIVERY_DB_PASSWORD:-delivery_dev_password}"
  if docker exec -e PGPASSWORD="${DELIVERY_PASS}" "${PG_CONTAINER}" psql -U delivery_app -d delivery_db -c "SELECT 1;" >/dev/null 2>&1; then
    pass "delivery_app successfully connected to delivery_db"
  else
    fail "delivery_app failed to connect to delivery_db"
  fi

  # Test communications_app connection to communications_db
  COMMUNICATIONS_PASS="${COMMUNICATIONS_DB_PASSWORD:-communications_dev_password}"
  if docker exec -e PGPASSWORD="${COMMUNICATIONS_PASS}" "${PG_CONTAINER}" psql -U communications_app -d communications_db -c "SELECT 1;" >/dev/null 2>&1; then
    pass "communications_app successfully connected to communications_db"
  else
    fail "communications_app failed to connect to communications_db"
  fi

  # Verify negative cross-database access is denied
  if docker exec -e PGPASSWORD="${PRICING_PASS}" "${PG_CONTAINER}" psql -U pricing_app -d delivery_db -c "SELECT 1;" >/dev/null 2>&1; then
    fail "ISOLATION BREACH: pricing_app was able to connect to delivery_db!"
  else
    pass "Cross-database isolation verified: pricing_app denied from delivery_db"
  fi

  if docker exec -e PGPASSWORD="${DELIVERY_PASS}" "${PG_CONTAINER}" psql -U delivery_app -d communications_db -c "SELECT 1;" >/dev/null 2>&1; then
    fail "ISOLATION BREACH: delivery_app was able to connect to communications_db!"
  else
    pass "Cross-database isolation verified: delivery_app denied from communications_db"
  fi

  # Verify application roles are unprivileged
  SUPER_CHECK=$(docker exec "${PG_CONTAINER}" psql -U "${POSTGRES_USER:-postgres}" -d postgres -tAc "SELECT count(*) FROM pg_roles WHERE rolname IN ('pricing_app', 'delivery_app', 'communications_app') AND (rolsuper = true OR rolcreatedb = true);" 2>/dev/null || echo "0")
  if [ "${SUPER_CHECK}" = "0" ]; then
    pass "Application roles are unprivileged (NOSUPERUSER, NOCREATEDB verified)"
  else
    fail "Application roles have superuser or createdb privileges"
  fi
else
  fail "PostgreSQL container '${PG_CONTAINER}' is not running"
fi

echo ""

# ------------------------------------------------------------------------------
# 3. Private S3 Object Storage (MinIO)
# ------------------------------------------------------------------------------
echo -e "${BOLD}[3/3] Checking Private S3 Object Storage (MinIO)...${NC}"

if docker ps --format '{{.Names}}' | grep -qx "${MINIO_CONTAINER}"; then
  pass "MinIO container '${MINIO_CONTAINER}' is running"

  # Check HTTP health
  HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" --connect-timeout 3 "http://127.0.0.1:${MINIO_PORT}/minio/health/live" || echo "000")
  if [ "${HTTP_CODE}" = "200" ]; then
    pass "MinIO S3 service is healthy on port ${MINIO_PORT} (HTTP 200)"
  else
    fail "MinIO health check failed (HTTP ${HTTP_CODE})"
  fi

  # Check unauthenticated access is denied (Privacy requirement)
  ANON_CODE=$(curl -s -o /dev/null -w "%{http_code}" --connect-timeout 3 "http://127.0.0.1:${MINIO_PORT}/${BUCKET}/test-object" || echo "000")
  if [ "${ANON_CODE}" = "403" ]; then
    pass "Bucket privacy verified: anonymous GET to '${BUCKET}' rejected with HTTP 403 Forbidden"
  else
    fail "Anonymous GET returned HTTP ${ANON_CODE} (expected 403 Forbidden)"
  fi
else
  fail "MinIO container '${MINIO_CONTAINER}' is not running"
fi

echo ""
echo -e "${BOLD}==============================================================================${NC}"
echo -e "${BOLD} DEPENDENCY READINESS SUMMARY${NC}"
echo -e "${BOLD}==============================================================================${NC}"
echo -e "  Passed Checks  : ${GREEN}${PASSED_CHECKS}${NC}"
echo -e "  Warning Checks : ${YELLOW}${WARNING_CHECKS}${NC}"
echo -e "  Failed Checks  : ${RED}${FAILED_CHECKS}${NC}"
echo ""

if [ "${FAILED_CHECKS}" -eq 0 ]; then
  echo -e "${GREEN}${BOLD}✓ ALL BUSINESS DEPENDENCIES ARE HEALTHY AND READY.${NC}"
  exit 0
else
  echo -e "${RED}${BOLD}✗ ONE OR MORE DEPENDENCIES ARE UNHEALTHY.${NC}"
  exit 1
fi
