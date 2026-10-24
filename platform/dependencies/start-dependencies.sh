#!/usr/bin/env bash
# ==============================================================================
# SwiftDrop Platform — Business Dependencies Startup Helper
# Starts Kafka KRaft, Business PostgreSQL, and MinIO private object storage.
# Safe & Idempotent: Never deletes existing volumes or overwrites identity data.
# ==============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
DEP_DIR="${REPO_ROOT}/platform/dependencies"
ENV_FILE="${DEP_DIR}/.env"

echo "=============================================================================="
echo " SwiftDrop Platform — Business Dependencies Startup"
echo "=============================================================================="

# 1. Ensure .env exists
if [ ! -f "${ENV_FILE}" ]; then
  echo "INFO: ${ENV_FILE} not found. Creating from .env.example..."
  cp "${DEP_DIR}/.env.example" "${ENV_FILE}"
fi

# Load environment
set -a
# shellcheck disable=SC1090
source "${ENV_FILE}"
set +a

KAFKA_PORT="${KAFKA_PORT:-9092}"
BUSINESS_DB_PORT="${BUSINESS_DB_PORT:-5439}"
MINIO_PORT="${MINIO_PORT:-9000}"
MINIO_CONSOLE_PORT="${MINIO_CONSOLE_PORT:-9001}"

echo "[1/4] Checking port availability on loopback (127.0.0.1)..."
check_port() {
  local port="$1"
  local svc="$2"
  local container="$3"
  if lsof -i ":${port}" -sTCP:LISTEN >/dev/null 2>&1; then
    # Check if our container is the one listening
    if docker ps --filter "name=${container}" --format '{{.Names}}' | grep -qx "${container}"; then
      echo "  Port ${port} is already bound by ${container}."
    else
      echo "ERROR: Port ${port} (${svc}) is already in use by another process or container."
      echo "Please resolve the conflict or adjust port in ${ENV_FILE} before proceeding."
      exit 1
    fi
  else
    echo "  Port ${port} (${svc}) is free."
  fi
}

check_port "${KAFKA_PORT}" "Kafka External" "${KAFKA_CONTAINER_NAME:-swiftdrop-kafka}"
check_port "${BUSINESS_DB_PORT}" "Business PostgreSQL" "${BUSINESS_DB_CONTAINER_NAME:-swiftdrop-business-postgres}"
check_port "${MINIO_PORT}" "MinIO API" "${MINIO_CONTAINER_NAME:-swiftdrop-minio}"
check_port "${MINIO_CONSOLE_PORT}" "MinIO Console" "${MINIO_CONTAINER_NAME:-swiftdrop-minio}"

echo ""
echo "[2/4] Starting dependency containers via Docker Compose..."
docker compose -f "${DEP_DIR}/docker-compose.dependencies.yml" \
  --project-directory "${DEP_DIR}" \
  --env-file "${ENV_FILE}" \
  up -d

echo ""
echo "[3/4] Waiting for service readiness..."

# Wait for Kafka
echo -n "  Waiting for Kafka KRaft broker..."
RETRIES=30
until docker exec "${KAFKA_CONTAINER_NAME:-swiftdrop-kafka}" /opt/kafka/bin/kafka-broker-api-versions.sh --bootstrap-server localhost:9092 >/dev/null 2>&1 || [ "${RETRIES}" -eq 0 ]; do
  sleep 2
  RETRIES=$((RETRIES - 1))
  echo -n "."
done
if [ "${RETRIES}" -eq 0 ]; then
  echo " [FAILED]"
  echo "ERROR: Kafka broker did not become ready in time."
  exit 1
fi
echo " [READY]"

# Wait for PostgreSQL
echo -n "  Waiting for Business PostgreSQL..."
RETRIES=30
until docker exec "${BUSINESS_DB_CONTAINER_NAME:-swiftdrop-business-postgres}" pg_isready -U "${POSTGRES_USER:-postgres}" >/dev/null 2>&1 || [ "${RETRIES}" -eq 0 ]; do
  sleep 1
  RETRIES=$((RETRIES - 1))
  echo -n "."
done
if [ "${RETRIES}" -eq 0 ]; then
  echo " [FAILED]"
  echo "ERROR: Business PostgreSQL did not become ready in time."
  exit 1
fi
echo " [READY]"

# Wait for MinIO
echo -n "  Waiting for MinIO Private Storage..."
RETRIES=30
until curl -s -f "http://127.0.0.1:${MINIO_PORT}/minio/health/live" >/dev/null 2>&1 || [ "${RETRIES}" -eq 0 ]; do
  sleep 1
  RETRIES=$((RETRIES - 1))
  echo -n "."
done
if [ "${RETRIES}" -eq 0 ]; then
  echo " [FAILED]"
  echo "ERROR: MinIO S3 API did not become ready in time."
  exit 1
fi
echo " [READY]"

# Provision private bucket swiftdrop-pod
echo "  Ensuring private bucket '${SWIFTDROP_STORAGE_BUCKET:-swiftdrop-pod}' exists..."
python3 "${DEP_DIR}/init-storage.py" >/dev/null 2>&1 || true

echo ""
echo "[4/4] Verifying Kafka lifecycle topic provisioning..."
docker compose -f "${DEP_DIR}/docker-compose.dependencies.yml" \
  --project-directory "${DEP_DIR}" \
  --env-file "${ENV_FILE}" \
  up kafka-init >/dev/null 2>&1 || true

echo "  Kafka topic '${KAFKA_TOPIC_PARCEL_LIFECYCLE:-parcel.lifecycle.v1}' verified."
echo ""
echo "=============================================================================="
echo " SwiftDrop Business Dependencies are UP & HEALTHY"
echo "=============================================================================="
echo " Connection Reference:"
echo "   Kafka Host Bootstrap      : 127.0.0.1:${KAFKA_PORT}"
echo "   Kafka Container Bootstrap : kafka:29092"
echo "   Lifecycle Topic           : ${KAFKA_TOPIC_PARCEL_LIFECYCLE:-parcel.lifecycle.v1}"
echo "   Dead-Letter Topic         : ${KAFKA_TOPIC_PARCEL_LIFECYCLE_DLT:-parcel.lifecycle.v1.dlt}"
echo ""
echo "   PostgreSQL Host Port      : 127.0.0.1:${BUSINESS_DB_PORT}"
echo "   Pricing Database          : jdbc:postgresql://localhost:${BUSINESS_DB_PORT}/pricing_db (user: pricing_app)"
echo "   Delivery Database         : jdbc:postgresql://localhost:${BUSINESS_DB_PORT}/delivery_db (user: delivery_app)"
echo "   Communications Database   : jdbc:postgresql://localhost:${BUSINESS_DB_PORT}/communications_db (user: communications_app)"
echo ""
echo "   MinIO S3 Endpoint         : http://127.0.0.1:${MINIO_PORT} (Container: http://minio:9000)"
echo "   MinIO Console             : http://127.0.0.1:${MINIO_CONSOLE_PORT}"
echo "   Private POD Bucket        : ${SWIFTDROP_STORAGE_BUCKET:-swiftdrop-pod}"
echo "=============================================================================="
