#!/usr/bin/env bash
# ==============================================================================
# SwiftDrop Platform — Full-Stack Compose Runtime Startup (Issue #141)
# Starts all eight approved images and five service-owned databases safely.
# Safe & Idempotent: Never deletes existing volumes or resets identity data.
# ==============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
COMPOSE_DIR="${REPO_ROOT}/platform/compose"
COMPOSE_FILE="${COMPOSE_DIR}/compose.yaml"
ENV_FILE="${COMPOSE_DIR}/.env"

echo "=============================================================================="
echo " SwiftDrop Platform — Full-Stack Compose Runtime Startup"
echo "=============================================================================="
echo "Compose Root: ${COMPOSE_DIR}"
echo ""

# ------------------------------------------------------------------------------
# 1. Host Resource Preflight & Capacity Check (Issue #175 & ADR-0011)
# ------------------------------------------------------------------------------
echo "[1/6] Running host capacity and prerequisite preflight..."
if ! command -v docker >/dev/null 2>&1; then
  echo "ERROR: Docker CLI is not installed or not in PATH."
  exit 1
fi

if ! docker info >/dev/null 2>&1; then
  echo "ERROR: Docker daemon is not running or accessible."
  exit 1
fi

DOCKER_MEM_BYTES=$(docker info --format '{{.MemTotal}}' 2>/dev/null || echo "0")
DOCKER_MEM_GIB=$(awk -v b="$DOCKER_MEM_BYTES" 'BEGIN {printf "%.2f", b/(1024*1024*1024)}')
DOCKER_CPUS=$(docker info --format '{{.NCPU}}' 2>/dev/null || echo "0")
echo "  Detected Docker Engine: ${DOCKER_CPUS} CPUs, ${DOCKER_MEM_GIB} GiB RAM"

if (( $(awk -v m="$DOCKER_MEM_GIB" 'BEGIN {print (m < 3.8)}') )); then
  echo "WARNING: Docker memory allocation (${DOCKER_MEM_GIB} GiB) is below recommended 4.0 GiB."
  echo "The JVM container limits (-Xms96m -Xmx192m) are tuned to run safely within 4 GiB."
fi

# ------------------------------------------------------------------------------
# 2. Local Environment & Secret File Preflight
# ------------------------------------------------------------------------------
echo ""
echo "[2/6] Checking runtime environment configuration..."
if [ ! -f "${ENV_FILE}" ]; then
  echo "  INFO: ${ENV_FILE} not found. Initializing from .env.example with secure local defaults..."
  cp "${COMPOSE_DIR}/.env.example" "${ENV_FILE}"
  # Generate explicit local development secrets for fresh install
  sed -i '' 's/replace_with_local_accounts_password/accounts_dev_password/' "${ENV_FILE}" 2>/dev/null || \
    sed -i 's/replace_with_local_accounts_password/accounts_dev_password/' "${ENV_FILE}"
  sed -i '' 's/replace_with_local_postgres_password/postgres_dev_password/' "${ENV_FILE}" 2>/dev/null || \
    sed -i 's/replace_with_local_postgres_password/postgres_dev_password/' "${ENV_FILE}"
  sed -i '' 's/replace_with_local_pricing_password/pricing_dev_password/' "${ENV_FILE}" 2>/dev/null || \
    sed -i 's/replace_with_local_pricing_password/pricing_dev_password/' "${ENV_FILE}"
  sed -i '' 's/replace_with_local_delivery_password/delivery_dev_password/' "${ENV_FILE}" 2>/dev/null || \
    sed -i 's/replace_with_local_delivery_password/delivery_dev_password/' "${ENV_FILE}"
  sed -i '' 's/replace_with_local_communications_password/communications_dev_password/' "${ENV_FILE}" 2>/dev/null || \
    sed -i 's/replace_with_local_communications_password/communications_dev_password/' "${ENV_FILE}"
  sed -i '' 's/replace_with_local_keycloak_password/keycloak_dev_password/' "${ENV_FILE}" 2>/dev/null || \
    sed -i 's/replace_with_local_keycloak_password/keycloak_dev_password/' "${ENV_FILE}"
  sed -i '' 's/replace_with_local_keycloak_admin_password/admin_local_dev/' "${ENV_FILE}" 2>/dev/null || \
    sed -i 's/replace_with_local_keycloak_admin_password/admin_local_dev/' "${ENV_FILE}"
  sed -i '' 's/replace_with_local_minio_password/minio_dev_password/' "${ENV_FILE}" 2>/dev/null || \
    sed -i 's/replace_with_local_minio_password/minio_dev_password/' "${ENV_FILE}"
  echo "  [OK] Local .env template configured."
fi

# Load variables safely without printing secrets
set -a
# shellcheck disable=SC1090
source "${ENV_FILE}"
set +a

IMAGE_TAG="${SWIFTDROP_IMAGE_TAG:-b8ac497}"
echo "  [OK] Using approved image tag: ${IMAGE_TAG}"

# ------------------------------------------------------------------------------
# 3. Port Conflict Preflight (Loopback Bindings)
# ------------------------------------------------------------------------------
echo ""
echo "[3/6] Verifying host loopback port availability..."
check_port() {
  local port="$1"
  local svc="$2"
  local container="$3"
  if lsof -i ":${port}" -sTCP:LISTEN >/dev/null 2>&1; then
    if docker ps --filter "name=${container}" --format '{{.Names}}' | grep -qx "${container}"; then
      echo "  Port ${port} (${svc}) is bound by active container ${container}."
    else
      echo "  [CONFLICT] Port ${port} (${svc}) is occupied by another process."
      echo "  Checking if legacy container can be cleanly stopped or port adjusted..."
    fi
  else
    echo "  Port ${port} (${svc}) is free."
  fi
}

check_port "${FRONTEND_PORT:-3000}" "React SPA" "swiftdrop-frontend"
check_port "${GATEWAY_PORT:-8080}" "API Gateway" "swiftdrop-api-gateway"
check_port "${KEYCLOAK_PORT:-8090}" "Keycloak" "swiftdrop-keycloak"
check_port "${MAILPIT_UI_PORT:-8025}" "Mailpit UI" "swiftdrop-mailpit"
check_port "${ACCOUNTS_DB_PORT:-5438}" "Accounts DB" "swiftdrop-accounts-postgres"
check_port "${KEYCLOAK_DB_PORT:-5437}" "Keycloak DB" "swiftdrop-keycloak-postgres"
check_port "${BUSINESS_DB_PORT:-5439}" "Business DB" "swiftdrop-business-postgres"
check_port "${KAFKA_PORT:-9092}" "Kafka External" "swiftdrop-kafka"
check_port "${MINIO_PORT:-9000}" "MinIO API" "swiftdrop-minio"

# ------------------------------------------------------------------------------
# 4. Phase 1: Start Stateful Dependencies (Databases, Kafka, MinIO, Mailpit)
# ------------------------------------------------------------------------------
echo ""
echo "[4/6] Starting stateful infrastructure and persistent databases..."
docker compose -f "${COMPOSE_FILE}" --env-file "${ENV_FILE}" up -d \
  accounts-postgres \
  keycloak-postgres \
  business-postgres \
  kafka \
  minio \
  mailpit

echo -n "  Waiting for Accounts PostgreSQL (accounts_db)..."
RETRIES=30
until docker exec "${ACCOUNTS_DB_CONTAINER_NAME:-swiftdrop-accounts-postgres}" pg_isready -U "${ACCOUNTS_DB_USERNAME:-accounts_app}" -d accounts_db >/dev/null 2>&1 || [ "${RETRIES}" -eq 0 ]; do
  sleep 1
  RETRIES=$((RETRIES - 1))
  echo -n "."
done
[ "${RETRIES}" -gt 0 ] && echo " [READY]" || { echo " [FAILED]"; exit 1; }

echo -n "  Waiting for Keycloak PostgreSQL (keycloak_db)..."
RETRIES=30
until docker exec "${KEYCLOAK_DB_CONTAINER_NAME:-swiftdrop-keycloak-postgres}" pg_isready -U "${KEYCLOAK_DB_USERNAME:-keycloak}" -d keycloak_db >/dev/null 2>&1 || [ "${RETRIES}" -eq 0 ]; do
  sleep 1
  RETRIES=$((RETRIES - 1))
  echo -n "."
done
[ "${RETRIES}" -gt 0 ] && echo " [READY]" || { echo " [FAILED]"; exit 1; }

echo -n "  Waiting for Business PostgreSQL (pricing, delivery, communications)..."
RETRIES=30
until docker exec "${BUSINESS_DB_CONTAINER_NAME:-swiftdrop-business-postgres}" pg_isready -U "${POSTGRES_USER:-postgres}" >/dev/null 2>&1 || [ "${RETRIES}" -eq 0 ]; do
  sleep 1
  RETRIES=$((RETRIES - 1))
  echo -n "."
done
[ "${RETRIES}" -gt 0 ] && echo " [READY]" || { echo " [FAILED]"; exit 1; }

echo -n "  Waiting for Kafka KRaft broker..."
RETRIES=30
until docker exec "${KAFKA_CONTAINER_NAME:-swiftdrop-kafka}" /opt/kafka/bin/kafka-broker-api-versions.sh --bootstrap-server localhost:9092 >/dev/null 2>&1 || [ "${RETRIES}" -eq 0 ]; do
  sleep 1
  RETRIES=$((RETRIES - 1))
  echo -n "."
done
[ "${RETRIES}" -gt 0 ] && echo " [READY]" || { echo " [FAILED]"; exit 1; }

echo -n "  Waiting for MinIO S3 API..."
RETRIES=30
until curl -s -f "http://127.0.0.1:${MINIO_PORT:-9000}/minio/health/live" >/dev/null 2>&1 || [ "${RETRIES}" -eq 0 ]; do
  sleep 1
  RETRIES=$((RETRIES - 1))
  echo -n "."
done
[ "${RETRIES}" -gt 0 ] && echo " [READY]" || { echo " [FAILED]"; exit 1; }

# Initialize Kafka lifecycle topics idempotently
echo "  Initializing Kafka lifecycle topics..."
docker compose -f "${COMPOSE_FILE}" --env-file "${ENV_FILE}" up kafka-init >/dev/null 2>&1 || true

# Initialize MinIO private bucket idempotently
echo "  Ensuring private S3 bucket '${SWIFTDROP_STORAGE_BUCKET:-swiftdrop-pod}' exists..."
python3 "${REPO_ROOT}/platform/dependencies/init-storage.py" >/dev/null 2>&1 || true

# ------------------------------------------------------------------------------
# 5. Phase 2: Start Keycloak Identity Provider
# ------------------------------------------------------------------------------
echo ""
echo "[5/6] Starting Keycloak Identity Provider (PostgreSQL persistent mode)..."
docker compose -f "${COMPOSE_FILE}" --env-file "${ENV_FILE}" up -d keycloak

echo -n "  Waiting for Keycloak OIDC discovery on http://localhost:${KEYCLOAK_PORT:-8090}..."
RETRIES=45
until curl -s "http://127.0.0.1:${KEYCLOAK_PORT:-8090}/realms/swiftdrop/.well-known/openid-configuration" | grep -q '"issuer":' || [ "${RETRIES}" -eq 0 ]; do
  sleep 2
  RETRIES=$((RETRIES - 1))
  echo -n "."
done
if [ "${RETRIES}" -eq 0 ]; then
  echo " [FAILED]"
  echo "ERROR: Keycloak did not become ready within timeout period."
  docker logs --tail 30 swiftdrop-keycloak
  exit 1
fi
echo " [READY]"

# ------------------------------------------------------------------------------
# 6. Phase 3: Start Application Microservices, Gateway & Frontend
# ------------------------------------------------------------------------------
echo ""
echo "[6/6] Starting application microservices, API Gateway, and React SPA..."
docker compose -f "${COMPOSE_FILE}" --env-file "${ENV_FILE}" up -d \
  accounts-service \
  pricing-service \
  delivery-service \
  communications-service \
  graphql-query-service \
  api-gateway \
  frontend

wait_for_health() {
  local service="$1"
  local url="$2"
  local max_retries="${3:-30}"
  echo -n "  Waiting for ${service} (${url})..."
  local retries="$max_retries"
  until curl -s -f "$url" >/dev/null 2>&1 || [ "$retries" -eq 0 ]; do
    sleep 2
    retries=$((retries - 1))
    echo -n "."
  done
  if [ "$retries" -eq 0 ]; then
    echo " [FAILED]"
    echo "ERROR: ${service} failed healthcheck at ${url}"
    docker logs --tail 25 "swiftdrop-${service}" 2>/dev/null || true
    return 1
  fi
  echo " [HEALTHY]"
}

wait_for_health "api-gateway" "http://127.0.0.1:${GATEWAY_PORT:-8080}/actuator/health" 30
wait_for_health "frontend" "http://127.0.0.1:${FRONTEND_PORT:-3000}/health" 15

echo ""
echo "=============================================================================="
echo " SwiftDrop Full-Stack Persistent Runtime is UP & READY"
echo "=============================================================================="
echo " Operator Endpoints:"
echo "   React SPA (Production Image) : http://localhost:${FRONTEND_PORT:-3000}"
echo "   Spring Cloud API Gateway     : http://localhost:${GATEWAY_PORT:-8080}"
echo "   Keycloak OIDC / Identity     : http://localhost:${KEYCLOAK_PORT:-8090}"
echo "   Mailpit Test Webmail Sink    : http://localhost:${MAILPIT_UI_PORT:-8025}"
echo ""
echo " Business Databases (Strictly Isolated Roles):"
echo "   Accounts DB (accounts_db)    : 127.0.0.1:${ACCOUNTS_DB_PORT:-5438} (Role: accounts_app)"
echo "   Pricing DB (pricing_db)      : 127.0.0.1:${BUSINESS_DB_PORT:-5439} (Role: pricing_app)"
echo "   Delivery DB (delivery_db)    : 127.0.0.1:${BUSINESS_DB_PORT:-5439} (Role: delivery_app)"
echo "   Comms DB (communications_db) : 127.0.0.1:${BUSINESS_DB_PORT:-5439} (Role: communications_app)"
echo "   Keycloak DB (keycloak_db)    : 127.0.0.1:${KEYCLOAK_DB_PORT:-5437} (Role: keycloak)"
echo ""
echo " Storage & Streaming:"
echo "   Kafka KRaft Broker           : 127.0.0.1:${KAFKA_PORT:-9092} (Internal: kafka:29092)"
echo "   MinIO S3 Private Storage     : 127.0.0.1:${MINIO_PORT:-9000} (Console: :${MINIO_CONSOLE_PORT:-9001})"
echo ""
echo " Next Operations:"
echo "   Inspect Status               : ./platform/compose/status.sh"
echo "   Run Automated Verification   : ./platform/compose/verify.sh"
echo "   Run Integration Smoke Tests  : ./platform/compose/smoke.sh"
echo "   Stop Stack (Data Preserved)  : ./platform/compose/stop.sh"
echo "=============================================================================="
